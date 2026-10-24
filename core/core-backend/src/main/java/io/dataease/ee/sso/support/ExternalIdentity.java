package io.dataease.ee.sso.support;

import java.io.Serial;
import java.io.Serializable;

/**
 * 外部身份源认证通过的用户
 *
 * @param origin  用户来源（EeConstants.ORIGIN_*）
 * @param sub     身份源侧唯一标识（平台用户 ID / OIDC sub / LDAP 账号等）
 * @param account 建议的 DataEase 账号
 * @param name    姓名
 * @param email   邮箱
 * @param phone   手机号
 */
public record ExternalIdentity(int origin, String sub, String account, String name, String email, String phone) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
