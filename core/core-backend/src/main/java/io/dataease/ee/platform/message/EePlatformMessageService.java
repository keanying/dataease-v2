package io.dataease.ee.platform.message;

import io.dataease.ee.sso.support.EeSsoUserManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按 DataEase 用户发送平台消息：根据 per_user_platform 绑定关系换算为平台用户 ID
 */
@Component
public class EePlatformMessageService {

    private final Map<Integer, EePlatformMessageSender> senders = new HashMap<>();

    private final EeSsoUserManage ssoUserManage;

    public EePlatformMessageService(List<EePlatformMessageSender> senders, EeSsoUserManage ssoUserManage) {
        senders.forEach(sender -> this.senders.put(sender.origin(), sender));
        this.ssoUserManage = ssoUserManage;
    }

    public EePlatformMessageSender sender(int origin) {
        EePlatformMessageSender sender = senders.get(origin);
        if (sender == null) {
            DEException.throwException(Translator.get("ee_platform_not_enabled"));
        }
        return sender;
    }

    public boolean available(int origin) {
        EePlatformMessageSender sender = senders.get(origin);
        return sender != null && sender.available();
    }

    /**
     * 发送给 DataEase 用户
     *
     * @return 未绑定该平台账号、因而未发送的用户 ID
     */
    public List<Long> sendToUsers(int origin, Collection<Long> uids, EePlatformMessage message) {
        if (CollectionUtils.isEmpty(uids)) {
            return List.of();
        }
        Map<Long, String> bound = ssoUserManage.platformIds(origin, uids);
        List<Long> missing = new ArrayList<>();
        for (Long uid : new LinkedHashSet<>(uids)) {
            if (!bound.containsKey(uid)) {
                missing.add(uid);
            }
        }
        if (!bound.isEmpty()) {
            sender(origin).sendToUsers(new LinkedHashSet<>(bound.values()), message);
        }
        return missing;
    }

    public void sendToGroups(int origin, Collection<String> groupIds, EePlatformMessage message) {
        if (CollectionUtils.isEmpty(groupIds)) {
            return;
        }
        sender(origin).sendToGroups(groupIds, message);
    }
}
