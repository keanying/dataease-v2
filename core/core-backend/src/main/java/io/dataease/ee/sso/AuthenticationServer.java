package io.dataease.ee.sso;

import io.dataease.api.xpack.settings.XpackAuthenticationApi;
import io.dataease.api.xpack.settings.request.XpackAuthenticationEditor;
import io.dataease.api.xpack.settings.vo.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证设置（系统管理员），{@code /setting/authentication/status} 为白名单
 */
@RestController("xpackAuthenticationServer")
@RequestMapping("/setting/authentication")
public class AuthenticationServer implements XpackAuthenticationApi {

    private final EeAuthenticationManage authenticationManage;

    public AuthenticationServer(EeAuthenticationManage authenticationManage) {
        this.authenticationManage = authenticationManage;
    }

    @Override
    public List<XpackAuthenticationVO> grid() {
        checkAdmin();
        return authenticationManage.grid();
    }

    @Override
    public void switchEnable(XpackAuthenticationEditor editor) {
        checkAdmin();
        authenticationManage.switchEnable(editor.getId(), editor.isEnable());
    }

    @Override
    public String saveOidc(XpackOidcVO editor) {
        checkAdmin();
        return authenticationManage.saveOidc(editor);
    }

    @Override
    public String saveCas(XpackCasVO editor) {
        checkAdmin();
        return authenticationManage.saveCas(editor);
    }

    @Override
    public String saveLdap(XpackLdapVO editor) {
        checkAdmin();
        return authenticationManage.saveLdap(editor);
    }

    @Override
    public String saveOauth2(XpackOauth2VO editor) {
        checkAdmin();
        return authenticationManage.saveOauth2(editor);
    }

    @Override
    public String saveSaml2(XpackSaml2VO editor) {
        checkAdmin();
        return authenticationManage.saveSaml2(editor);
    }

    @Override
    public XpackSaml2VO samlInfo() {
        checkAdmin();
        return authenticationManage.samlInfo();
    }

    @Override
    public XpackOidcVO oidcInfo() {
        checkAdmin();
        return authenticationManage.info(EeSsoConstants.OIDC, XpackOidcVO.class);
    }

    @Override
    public XpackCasVO casInfo() {
        checkAdmin();
        return authenticationManage.info(EeSsoConstants.CAS, XpackCasVO.class);
    }

    @Override
    public XpackLdapVO ldapInfo() {
        checkAdmin();
        return authenticationManage.info(EeSsoConstants.LDAP, XpackLdapVO.class);
    }

    @Override
    public XpackOauth2VO oauth2Info() {
        checkAdmin();
        return authenticationManage.info(EeSsoConstants.OAUTH2, XpackOauth2VO.class);
    }

    @Override
    public String validateOidc(XpackOidcVO editor) {
        checkAdmin();
        return authenticationManage.validate(EeSsoConstants.OIDC, editor);
    }

    @Override
    public String validateCas(XpackCasVO editor) {
        checkAdmin();
        return authenticationManage.validate(EeSsoConstants.CAS, editor);
    }

    @Override
    public String validateLdap(XpackLdapVO editor) {
        checkAdmin();
        return authenticationManage.validate(EeSsoConstants.LDAP, editor);
    }

    @Override
    public String validateOauth2(XpackOauth2VO editor) {
        checkAdmin();
        return authenticationManage.validate(EeSsoConstants.OAUTH2, editor);
    }

    @Override
    public String validateSaml2(XpackSaml2VO editor) {
        checkAdmin();
        return authenticationManage.validate(EeSsoConstants.SAML2, editor);
    }

    @Override
    public String validate(Long id) {
        checkAdmin();
        return authenticationManage.validateId(id);
    }

    @Override
    public List<XpackAuthenticationStatusVO> status() {
        return authenticationManage.status();
    }

    private void checkAdmin() {
        if (!V3UserUtil.isSysAdmin()) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }
}
