package io.dataease.ee.auth;

import io.dataease.api.permissions.login.api.LoginApi;
import io.dataease.api.permissions.login.dto.MfaLoginDTO;
import io.dataease.api.permissions.login.dto.PwdLoginDTO;
import io.dataease.api.permissions.login.vo.MfaQrVO;
import io.dataease.api.permissions.user.dto.ModifyPwdRequest;
import io.dataease.auth.vo.TokenVO;
import io.dataease.constant.AuthConstant;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.user.EeUserManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.RsaUtils;
import io.dataease.utils.ServletUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、登出、token 续期、MFA 登录与修改失效密码。
 */
@RestController("loginServer")
@RequestMapping
public class LoginServer implements LoginApi {

    private final EeLoginManage loginManage;

    private final EeTokenManage tokenManage;

    private final EeMfaManage mfaManage;

    private final EeUserManage userManage;

    public LoginServer(EeLoginManage loginManage, EeTokenManage tokenManage, EeMfaManage mfaManage, EeUserManage userManage) {
        this.loginManage = loginManage;
        this.tokenManage = tokenManage;
        this.mfaManage = mfaManage;
        this.userManage = userManage;
    }

    @Override
    public TokenVO localLogin(PwdLoginDTO dto) {
        String account = decrypt(dto.getName());
        String pwd = decrypt(dto.getPwd());
        int origin = dto.getOrigin() == null ? EeConstants.ORIGIN_LOCAL : dto.getOrigin();
        if (origin == EeConstants.ORIGIN_LDAP) {
            return loginManage.externalPasswordLogin(EeConstants.ORIGIN_LDAP, account, pwd);
        }
        return loginManage.localLogin(account, pwd);
    }

    @Override
    public TokenVO refresh() {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException("token is empty");
        }
        return loginManage.issue(uid);
    }

    @Override
    public TokenVO platformLogin(Integer origin) {
        return loginManage.platformLogin(origin == null ? -1 : origin);
    }

    @Override
    public void logout() {
        tokenManage.destroy(ServletUtils.getToken());
    }

    @Override
    public MfaQrVO mfaQr(Long id) {
        return mfaManage.loginQr(id);
    }

    @Override
    public TokenVO mfaLogin(MfaLoginDTO dto) {
        mfaManage.finishLogin(dto.getId(), dto.getCode());
        return loginManage.issue(dto.getId());
    }

    @Override
    public void modifyInvalidPwd(ModifyPwdRequest request) {
        String token = ServletUtils.getToken();
        Long[] identity = tokenManage.verify(token);
        if (request.getUid() != null && !request.getUid().equals(identity[0])) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        userManage.modifyPwd(identity[0], decrypt(request.getPwd()), decrypt(request.getNewPwd()));
        tokenManage.destroy(token);
    }

    public static String decrypt(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        try {
            return RsaUtils.decryptStr(value);
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_rsa_changed"));
            return null;
        }
    }

    /**
     * 当前请求携带的登录 token
     */
    public static String currentToken() {
        return ServletUtils.getHead(AuthConstant.TOKEN_KEY);
    }
}
