package io.dataease.ee.auth;

import io.dataease.constant.AuthConstant;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.exception.DEException;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import io.dataease.utils.RsaUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 登录 token（X-DE-TOKEN），支持系统管理员代理访问组织（X-DE-ADMIN-PROXY）。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class UserTokenAuthenticator implements EeRequestAuthenticator {

    private final EeTokenManage tokenManage;

    private final EeOrgManage orgManage;

    public UserTokenAuthenticator(EeTokenManage tokenManage, EeOrgManage orgManage) {
        this.tokenManage = tokenManage;
        this.orgManage = orgManage;
    }

    @Override
    public boolean supports(HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getHeader(AuthConstant.TOKEN_KEY));
    }

    @Override
    public void authenticate(HttpServletRequest request) {
        Long[] identity = tokenManage.verify(request.getHeader(AuthConstant.TOKEN_KEY));
        Long uid = identity[0];
        Long tokenOid = identity[1];
        Long oid = orgManage.resolveCurrentOid(uid);
        if (!java.util.Objects.equals(tokenOid, oid)) {
            // 所属组织已变更，要求重新登录
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "permission has been changed");
        }
        V3UserUtil.setContext(uid, oid);

        String proxy = request.getHeader(EeConstants.ADMIN_PROXY_HEADER);
        if (StringUtils.isNotBlank(proxy)) {
            if (!V3UserUtil.isSysAdmin(uid)) {
                DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
            }
            Long proxyOid;
            try {
                proxyOid = Long.parseLong(RsaUtils.decryptStr(proxy).trim());
            } catch (Exception e) {
                DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), "invalid proxy header");
                return;
            }
            if (orgManage.exists(proxyOid)) {
                V3UserUtil.setProxy(proxyOid);
            }
        }
    }
}
