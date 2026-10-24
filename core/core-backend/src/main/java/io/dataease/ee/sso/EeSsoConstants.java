package io.dataease.ee.sso;

import io.dataease.ee.common.EeConstants;

import java.util.List;

/**
 * 认证集成与平台对接模块常量
 */
public final class EeSsoConstants {

    private EeSsoConstants() {
    }

    /**
     * 认证设置（ee_auth_config.name）
     */
    public static final String LDAP = "ldap";

    public static final String OIDC = "oidc";

    public static final String CAS = "cas";

    public static final String OAUTH2 = "oauth2";

    public static final String SAML2 = "saml2";

    /**
     * 平台对接（ee_auth_config.name）
     */
    public static final String DINGTALK = "dingtalk";

    public static final String LARK = "lark";

    public static final String LARKSUITE = "larksuite";

    public static final String WECOM = "wecom";

    public static final String QRCODE = "qrcode";

    /**
     * 认证设置卡片的展示顺序（grid 始终返回全部 5 项）
     */
    public static final List<String> AUTH_PROVIDERS = List.of(OIDC, CAS, LDAP, OAUTH2, SAML2);

    public static final List<String> PLATFORMS = List.of(WECOM, DINGTALK, LARK, LARKSUITE);

    /**
     * 认证状态接口返回顺序
     */
    public static final List<String> STATUS_NAMES = List.of(LDAP, OIDC, CAS, OAUTH2, SAML2, WECOM, DINGTALK, LARK, LARKSUITE, QRCODE);

    /**
     * 第三方登录相关的系统参数
     */
    public static final String KEY_AUTO_CREATE_USER = "basic.autoCreateUser";

    public static final String KEY_PLATFORM_OID = "basic.platformOid";

    public static final String KEY_PLATFORM_RID = "basic.platformRid";

    /**
     * 网关注入的共享密钥请求头，用于确认 X-Userinfo / X-CAS-USER 确实来自受信任的网关
     */
    public static final String GATEWAY_SECRET_HEADER = "X-DE-GATEWAY-SECRET";

    /**
     * 网关前缀
     */
    public static final String OIDC_PREFIX = "/oidcbi";

    public static final String CAS_PREFIX = "/casbi";

    public static final String OIDC_LOGOUT_PATH = OIDC_PREFIX + "/oidc/logout";

    public static final String CAS_LOGOUT_PATH = CAS_PREFIX + "/cas/logout";

    public static final String CAS_CALLBACK_PATH = CAS_PREFIX + "/cas_callback";

    /**
     * 请求属性：内置网关（无 APISIX）识别出的外部身份
     */
    public static final String IDENTITY_ATTRIBUTE = "ee.sso.identity";

    /**
     * 缓存名称（同一缓存使用固定的过期时间）
     */
    public static final String CACHE_TICKET_USED = "ee_sso_ticket_used";

    public static final String CACHE_SAML_REQUEST = "ee_sso_saml_request";

    public static final String CACHE_PLATFORM_TOKEN = "ee_platform_access_token";

    public static final long TICKET_TTL_MINUTES = 5;

    public static final long PLATFORM_TOKEN_TTL_MINUTES = 90;

    public static String nameOfOrigin(int origin) {
        return switch (origin) {
            case EeConstants.ORIGIN_LDAP -> LDAP;
            case EeConstants.ORIGIN_OIDC -> OIDC;
            case EeConstants.ORIGIN_CAS -> CAS;
            case EeConstants.ORIGIN_LARK -> LARK;
            case EeConstants.ORIGIN_DINGTALK -> DINGTALK;
            case EeConstants.ORIGIN_WECOM -> WECOM;
            case EeConstants.ORIGIN_LARKSUITE -> LARKSUITE;
            case EeConstants.ORIGIN_OAUTH2 -> OAUTH2;
            case EeConstants.ORIGIN_SAML -> SAML2;
            default -> null;
        };
    }

    /**
     * 是否以“账号”识别用户的来源（LDAP/OIDC/CAS/OAuth2/SAML2）；平台（钉钉等）以平台用户 ID 识别
     */
    public static boolean accountBased(int origin) {
        return origin == EeConstants.ORIGIN_LDAP || origin == EeConstants.ORIGIN_OIDC || origin == EeConstants.ORIGIN_CAS
                || origin == EeConstants.ORIGIN_OAUTH2 || origin == EeConstants.ORIGIN_SAML;
    }
}
