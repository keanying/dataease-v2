package io.dataease.ee.platform;

import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;

/**
 * 平台对接接口的访问控制
 */
public final class EePlatformAccess {

    private EePlatformAccess() {
    }

    /**
     * 平台设置仅系统管理员可维护
     */
    public static void checkAdmin() {
        if (!V3UserUtil.isSysAdmin()) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    public static void checkLogin() {
        if (V3UserUtil.getUid() == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }
}
