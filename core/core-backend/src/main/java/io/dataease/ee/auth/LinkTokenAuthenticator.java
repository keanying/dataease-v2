package io.dataease.ee.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.constant.AuthConstant;
import io.dataease.exception.DEException;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import io.dataease.share.manage.ShareSecretManage;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 公共链接 token（X-DE-LINK-TOKEN），以分享者身份访问，访问范围由 {@code LinkInterceptor}/{@code DeLinkAop} 限制。
 */
@Component
@Order(10)
public class LinkTokenAuthenticator implements EeRequestAuthenticator {

    private final ShareSecretManage shareSecretManage;

    public LinkTokenAuthenticator(ShareSecretManage shareSecretManage) {
        this.shareSecretManage = shareSecretManage;
    }

    @Override
    public boolean supports(HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getHeader(AuthConstant.LINK_TOKEN_KEY));
    }

    @Override
    public void authenticate(HttpServletRequest request) {
        String linkToken = request.getHeader(AuthConstant.LINK_TOKEN_KEY);
        DecodedJWT jwt;
        try {
            jwt = JWT.decode(linkToken);
        } catch (JWTVerificationException e) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "link token is invalid");
            return;
        }
        Long uid = jwt.getClaim("uid").asLong();
        Long oid = jwt.getClaim("oid").asLong();
        Long resourceId = jwt.getClaim("resourceId").asLong();
        if (ObjectUtils.anyNull(uid, resourceId)) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "link token is invalid");
        }
        String secret = shareSecretManage.getSecret(resourceId, uid);
        if (StringUtils.isBlank(secret)) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "link token is invalid");
        }
        try {
            JWT.require(Algorithm.HMAC256(secret)).build().verify(linkToken);
        } catch (JWTVerificationException e) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "link token is invalid");
        }
        V3UserUtil.setContext(uid, oid);
    }
}
