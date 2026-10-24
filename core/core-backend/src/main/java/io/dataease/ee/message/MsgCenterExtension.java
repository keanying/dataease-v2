package io.dataease.ee.message;

import io.dataease.ee.interact.EeExtension;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.LogUtil;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 替换核心 {@code MsgCenterServer.count()}：汇总全部 {@link EeMsgCountProvider}
 */
@EeExtension("msgCenterServer")
public class MsgCenterExtension {

    private final ObjectProvider<EeMsgCountProvider> providers;

    public MsgCenterExtension(ObjectProvider<EeMsgCountProvider> providers) {
        this.providers = providers;
    }

    public long count() {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            return 0L;
        }
        Long oid = V3UserUtil.getOid();
        long total = 0L;
        for (EeMsgCountProvider provider : providers.orderedStream().toList()) {
            try {
                total += Math.max(0L, provider.count(uid, oid));
            } catch (Exception e) {
                LogUtil.warn("message count provider failed: " + provider.getClass().getName() + " " + e.getMessage());
            }
        }
        return total;
    }
}
