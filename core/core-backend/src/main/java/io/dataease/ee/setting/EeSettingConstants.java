package io.dataease.ee.setting;

/**
 * 系统设置模块常量
 */
public final class EeSettingConstants {

    private EeSettingConstants() {
    }

    /**
     * 外观设置在 core_sys_setting 中的键前缀（避免与其它设置键冲突）
     */
    public static final String APPEARANCE_PREFIX = "ui.";

    public static final String EMAIL_PREFIX = "email.";

    public static final String MFA_PREFIX = "mfa.";

    public static final String HMAC_PREFIX = "hmac.";

    public static final String HMAC_ENABLE = "hmac.enable";

    public static final String HMAC_SECRET = "hmac.secretKey";

    public static final String HMAC_CLOCK_SKEW = "hmac.clockSkew";

    public static final String MFA_STATUS = "mfa.status";

    public static final String MFA_PLATFORM_ENABLE = "mfa.platformEnable";

    public static final String MFA_OTP_NAME = "mfa.otpName";

    public static final String MFA_RATE = "mfa.rate";

    /**
     * 每个用户最多 API Key 数
     */
    public static final int API_KEY_LIMIT = 5;

    /**
     * 请求属性：当前请求的认证方式（apikey / embedded）
     */
    public static final String AUTH_TYPE_ATTR = "de.ee.authType";

    public static final String AUTH_TYPE_API_KEY = "apikey";

    public static final String AUTH_TYPE_EMBEDDED = "embedded";

    /**
     * 请求属性：已校验的嵌入式 token 声明
     */
    public static final String EMBEDDED_CLAIMS_ATTR = "de.ee.embeddedClaims";
}
