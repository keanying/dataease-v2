package io.dataease.ee.report.notify;

import io.dataease.api.permissions.user.api.UserApi;
import io.dataease.api.permissions.user.dto.UserReciRequest;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按接收人配置把消息分发到各渠道（{@link EeNotifyChannel}）。
 */
@Component
public class EeNotifyDispatcher {

    private final ObjectProvider<EeNotifyChannel> channelProvider;

    private final ObjectProvider<UserApi> userApiProvider;

    public EeNotifyDispatcher(ObjectProvider<EeNotifyChannel> channelProvider, ObjectProvider<UserApi> userApiProvider) {
        this.channelProvider = channelProvider;
        this.userApiProvider = userApiProvider;
    }

    /**
     * 当前可用的渠道
     */
    public Map<String, EeNotifyChannel> channels() {
        Map<String, EeNotifyChannel> map = new HashMap<>();
        channelProvider.orderedStream().forEach(channel -> map.putIfAbsent(channel.type(), channel));
        return map;
    }

    /**
     * 解析接收人中的内部用户（启用状态）
     */
    public List<Long> resolveUserIds(EeRecipients recipients) {
        if (recipients == null || (CollectionUtils.isEmpty(recipients.getUids()) && CollectionUtils.isEmpty(recipients.getRids()))) {
            return new ArrayList<>();
        }
        UserReciRequest request = new UserReciRequest();
        request.setUidList(recipients.getUids());
        request.setRidList(recipients.getRids());
        List<Long> ids = userApiProvider.getObject().getRecipientUserIds(request);
        return ids == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /**
     * 分发消息
     *
     * @param base       消息模板（title/content/attachments/source 等）
     * @param recipients 接收人
     * @param withGroups 是否包含额外邮箱、群与 Webhook（按接收人分别渲染时，只在第一份中发送这些公共目标）
     */
    public EeNotifyResult dispatch(EeNotifyMessage base, EeRecipients recipients, boolean withGroups) {
        EeNotifyResult result = new EeNotifyResult();
        Map<String, LinkedHashSet<String>> targets = new LinkedHashMap<>();
        Map<String, LinkedHashSet<Long>> targetUids = new HashMap<>();

        List<Long> userIds = resolveUserIds(recipients);
        List<Integer> flags = recipients.getReciFlags() == null ? Collections.emptyList() : recipients.getReciFlags();
        if (!userIds.isEmpty() && !flags.isEmpty()) {
            List<Map<String, Object>> users = userApiProvider.getObject().listUserInfosByIds(userIds);
            for (Integer flag : new LinkedHashSet<>(flags)) {
                String type = EeNotifyTypes.ofReciFlag(flag);
                if (type == null) {
                    continue;
                }
                List<String> missing = new ArrayList<>();
                for (Map<String, Object> user : users) {
                    String target = userTarget(type, user);
                    if (StringUtils.isBlank(target)) {
                        missing.add(String.valueOf(user.get("name")));
                        continue;
                    }
                    targets.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(target);
                    targetUids.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(Long.valueOf(String.valueOf(user.get("id"))));
                }
                if (!missing.isEmpty()) {
                    result.warn(Translator.get("ee_report_notify_missing_target", type, String.join(",", missing)));
                }
            }
        }
        if (withGroups) {
            addAll(targets, EeNotifyTypes.EMAIL, recipients.getEmails());
            addAll(targets, EeNotifyTypes.DINGTALK_GROUP, recipients.getDingtalkGroups());
            addAll(targets, EeNotifyTypes.LARK_GROUP, recipients.getLarkGroups());
            addAll(targets, EeNotifyTypes.LARKSUITE_GROUP, recipients.getLarksuiteGroups());
            addAll(targets, EeNotifyTypes.WEBHOOK, recipients.getWebhooks());
        }
        if (targets.isEmpty()) {
            result.error(Translator.get("ee_report_notify_no_target"));
            return result;
        }
        Map<String, EeNotifyChannel> channels = channels();
        for (Map.Entry<String, LinkedHashSet<String>> entry : targets.entrySet()) {
            String type = entry.getKey();
            EeNotifyChannel channel = channels.get(type);
            if (channel == null) {
                result.error(Translator.get("ee_report_notify_channel_unavailable", type));
                continue;
            }
            EeNotifyMessage msg = base.copyFor(type, new ArrayList<>(entry.getValue()),
                    new ArrayList<>(targetUids.getOrDefault(type, new LinkedHashSet<>())));
            try {
                channel.send(msg);
                result.success();
            } catch (Exception e) {
                LogUtil.error("send " + type + " message failed: " + e.getMessage(), e);
                result.error(Translator.get("ee_report_notify_send_failed", type, rootMessage(e)));
            }
        }
        return result;
    }

    private String userTarget(String type, Map<String, Object> user) {
        if (EeNotifyTypes.EMAIL.equals(type)) {
            Object email = user.get("email");
            return email == null ? null : email.toString();
        }
        Integer origin = EeNotifyTypes.platformOrigin(type);
        Object platforms = user.get("platforms");
        if (origin == null || !(platforms instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(origin);
        if (value == null) {
            value = map.get(String.valueOf(origin));
        }
        return ObjectUtils.isEmpty(value) ? null : value.toString();
    }

    private void addAll(Map<String, LinkedHashSet<String>> targets, String type, List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                targets.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(value.trim());
            }
        }
    }

    public static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (StringUtils.isBlank(msg)) {
            msg = e.getMessage();
        }
        return StringUtils.isBlank(msg) ? cause.getClass().getSimpleName() : msg;
    }
}
