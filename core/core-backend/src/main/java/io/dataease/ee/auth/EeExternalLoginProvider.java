package io.dataease.ee.auth;

import io.dataease.ee.permissions.dao.entity.PerUser;

/**
 * 外部身份登录（LDAP、OIDC、CAS、OAuth2、SAML2、钉钉、飞书、企业微信等）。
 * 实现类以 Spring Bean 形式注册，按 {@link #origin()} 匹配登录来源。
 */
public interface EeExternalLoginProvider {

    /**
     * 用户来源编码，见 {@link io.dataease.ee.common.EeConstants}
     */
    int origin();

    /**
     * 第三方回调后的登录：从当前请求（网关头、临时 token 等）解析出用户，不存在时按配置自动创建
     */
    default PerUser platformLogin() {
        throw new UnsupportedOperationException();
    }

    /**
     * 账号密码方式的外部认证（如 LDAP）；校验失败抛出异常
     */
    default PerUser passwordLogin(String account, String pwd) {
        throw new UnsupportedOperationException();
    }
}
