package io.dataease.ee.setting;

import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;

/**
 * 系统设置模块公共方法
 */
public final class EeSettingSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private EeSettingSupport() {
    }

    /**
     * 仅系统管理员（含代理组织状态下的系统管理员）
     */
    public static void checkSysAdmin() {
        Long uid = V3UserUtil.getUid();
        if (uid == null || !V3UserUtil.isSysAdmin(uid)) {
            DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), Translator.get("ee_no_permission"));
        }
    }

    /**
     * 随机字母数字串（SecureRandom）
     */
    public static String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    public static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    /**
     * 当前请求的认证方式（{@link EeSettingConstants#AUTH_TYPE_API_KEY} / {@link EeSettingConstants#AUTH_TYPE_EMBEDDED}），普通登录为 null
     */
    public static String currentAuthType() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object type = request.getAttribute(EeSettingConstants.AUTH_TYPE_ATTR);
        return type == null ? null : type.toString();
    }
}
