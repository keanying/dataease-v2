package io.dataease.ee.message;

import io.dataease.api.communicate.dto.MessageDTO;
import io.dataease.api.communicate.dto.MessageFile;
import io.dataease.api.permissions.user.api.UserApi;
import io.dataease.constant.MessageEnum;
import io.dataease.ee.report.notify.EeNotifyAttachment;
import io.dataease.ee.report.notify.EeNotifyChannel;
import io.dataease.ee.report.notify.EeNotifyDispatcher;
import io.dataease.ee.report.notify.EeNotifyMessage;
import io.dataease.ee.report.notify.EeNotifyTypes;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 邮件与 IM 消息渠道：把 {@link MessageDTO} 转换为 {@link EeNotifyMessage}，委托给定时报告/阈值告警共用的
 * {@link EeNotifyChannel}（{@link EeNotifyDispatcher#channels()}），不重复实现发送逻辑。
 *
 * <p>{@code recipientList} 的含义：</p>
 * <ul>
 *     <li>EMAIL：邮箱地址；纯数字或 {@code uid:<ID>} 表示 DataEase 用户 ID，取用户邮箱；</li>
 *     <li>WECOM / DINGTALK / LARK / LARKSUITE：平台用户 ID（与用户绑定的平台账号一致）；
 *     {@code uid:<ID>} 表示 DataEase 用户 ID，取其绑定的平台账号；</li>
 *     <li>DINGTALKGROUP / LARKGROUP / LARKSUITEGROUP：群 chat_id。</li>
 * </ul>
 * <p>{@code content} 按 UTF-8 解码为 HTML 正文；{@code messageFileList}、{@code fileList} 作为附件（图片在邮件中内嵌显示）。
 * 部分接收人缺少邮箱/平台账号时跳过并在异常信息中说明；渠道未配置或发送失败时抛出 {@link DEException}。</p>
 */
@Component
public class EeNotifyMessageChannel implements EeMessageChannel {

    private static final String UID_PREFIX = "uid:";

    private static final Map<MessageEnum, String> TYPES = Map.of(
            MessageEnum.EMAIL, EeNotifyTypes.EMAIL,
            MessageEnum.WECOM, EeNotifyTypes.WECOM,
            MessageEnum.DINGTALK, EeNotifyTypes.DINGTALK,
            MessageEnum.LARK, EeNotifyTypes.LARK,
            MessageEnum.LARKSUITE, EeNotifyTypes.LARKSUITE,
            MessageEnum.DINGTALKGROUP, EeNotifyTypes.DINGTALK_GROUP,
            MessageEnum.LARKGROUP, EeNotifyTypes.LARK_GROUP,
            MessageEnum.LARKSUITEGROUP, EeNotifyTypes.LARKSUITE_GROUP);

    private final EeNotifyDispatcher dispatcher;

    private final ObjectProvider<UserApi> userApi;

    public EeNotifyMessageChannel(EeNotifyDispatcher dispatcher, ObjectProvider<UserApi> userApi) {
        this.dispatcher = dispatcher;
        this.userApi = userApi;
    }

    @Override
    public boolean supports(MessageEnum messageEnum) {
        return messageEnum != null && TYPES.containsKey(messageEnum);
    }

    @Override
    public void send(MessageDTO message) {
        String type = TYPES.get(message.getMessageEnum());
        EeNotifyChannel channel = dispatcher.channels().get(type);
        if (channel == null) {
            DEException.throwException(Translator.get("ee_report_notify_channel_unavailable", type));
        }
        List<String> missing = new ArrayList<>();
        List<Long> uids = new ArrayList<>();
        List<String> targets = targets(type, message.getRecipientList(), uids, missing);
        if (targets.isEmpty()) {
            DEException.throwException(missing.isEmpty() ? Translator.get("ee_report_notify_no_target")
                    : Translator.get("ee_report_notify_missing_target", type, String.join(",", missing)));
        }
        EeNotifyMessage msg = new EeNotifyMessage();
        msg.setType(type);
        msg.setTargets(targets);
        msg.setUids(uids);
        msg.setTitle(StringUtils.defaultString(message.getTitle()));
        msg.setContent(message.getContent() == null ? "" : new String(message.getContent(), StandardCharsets.UTF_8));
        msg.setAttachments(attachments(message));
        msg.setSource("message");
        msg.setSourceId(message.getMessageId());
        msg.setOid(V3UserUtil.getOid());
        try {
            channel.send(msg);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_report_notify_send_failed", type, EeNotifyDispatcher.rootMessage(e)));
        }
        if (!missing.isEmpty()) {
            // 已发送给其余接收人，缺少地址的接收人以异常告知调用方
            DEException.throwException(Translator.get("ee_report_notify_missing_target", type, String.join(",", missing)));
        }
    }

    /**
     * 解析接收目标：{@code uid:<ID>}（邮件还接受纯数字）换成用户的邮箱/平台账号，其余原样作为渠道目标
     */
    private List<String> targets(String type, List<String> recipients, List<Long> uids, List<String> missing) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        List<Long> userIds = new ArrayList<>();
        boolean email = EeNotifyTypes.EMAIL.equals(type);
        for (String raw : CollectionUtils.emptyIfNull(recipients)) {
            String r = StringUtils.trimToEmpty(raw);
            if (r.isEmpty()) {
                continue;
            }
            String id = StringUtils.startsWithIgnoreCase(r, UID_PREFIX) ? r.substring(UID_PREFIX.length()).trim()
                    : (email && StringUtils.isNumeric(r) ? r : null);
            if (id != null && StringUtils.isNumeric(id)) {
                userIds.add(Long.parseLong(id));
            } else {
                targets.add(r);
            }
        }
        if (!userIds.isEmpty()) {
            UserApi api = userApi.getIfAvailable();
            List<Map<String, Object>> users = api == null ? List.of() : ObjectUtils.defaultIfNull(api.listUserInfosByIds(userIds), List.of());
            Set<Long> found = new HashSet<>();
            for (Map<String, Object> user : users) {
                Long uid = Long.valueOf(String.valueOf(user.get("id")));
                found.add(uid);
                String target = userTarget(type, user);
                if (StringUtils.isBlank(target)) {
                    missing.add(String.valueOf(user.get("name")));
                } else {
                    targets.add(target);
                    uids.add(uid);
                }
            }
            userIds.stream().filter(uid -> !found.contains(uid)).forEach(uid -> missing.add(String.valueOf(uid)));
        }
        return new ArrayList<>(targets);
    }

    private static String userTarget(String type, Map<String, Object> user) {
        if (EeNotifyTypes.EMAIL.equals(type)) {
            Object email = user.get("email");
            return email == null ? null : email.toString();
        }
        Integer origin = EeNotifyTypes.platformOrigin(type);
        if (origin == null || !(user.get("platforms") instanceof Map<?, ?> platforms)) {
            return null;
        }
        Object value = platforms.get(origin);
        if (value == null) {
            value = platforms.get(String.valueOf(origin));
        }
        return ObjectUtils.isEmpty(value) ? null : value.toString();
    }

    private static List<EeNotifyAttachment> attachments(MessageDTO message) {
        List<EeNotifyAttachment> list = new ArrayList<>();
        int index = 0;
        for (MessageFile file : CollectionUtils.emptyIfNull(message.getMessageFileList())) {
            if (file != null && file.getBody() != null) {
                list.add(attachment(StringUtils.defaultIfBlank(file.getFileName(), "attachment"), file.getBody(), index++));
            }
        }
        for (File file : CollectionUtils.emptyIfNull(message.getFileList())) {
            if (file != null && file.isFile()) {
                try {
                    list.add(attachment(file.getName(), Files.readAllBytes(file.toPath()), index++));
                } catch (Exception e) {
                    DEException.throwException(e);
                }
            }
        }
        return list;
    }

    private static EeNotifyAttachment attachment(String name, byte[] data, int index) {
        String contentType = StringUtils.defaultIfBlank(URLConnection.guessContentTypeFromName(name), "application/octet-stream");
        boolean image = StringUtils.startsWith(contentType, "image/");
        return new EeNotifyAttachment(name, contentType, data, image, image ? "de-message-image-" + index : null);
    }
}
