package io.dataease.ee.sso.ldap;

import io.dataease.ee.auth.EeExternalLoginProvider;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.sso.support.EeSsoUserManage;
import org.springframework.stereotype.Component;

/**
 * LDAP 账号密码登录（{@code POST /login/localLogin}，origin=1）
 */
@Component
public class LdapLoginProvider implements EeExternalLoginProvider {

    private final EeLdapManage ldapManage;

    private final EeSsoUserManage ssoUserManage;

    public LdapLoginProvider(EeLdapManage ldapManage, EeSsoUserManage ssoUserManage) {
        this.ldapManage = ldapManage;
        this.ssoUserManage = ssoUserManage;
    }

    @Override
    public int origin() {
        return EeConstants.ORIGIN_LDAP;
    }

    @Override
    public PerUser passwordLogin(String account, String pwd) {
        return ssoUserManage.resolve(ldapManage.authenticate(account, pwd));
    }
}
