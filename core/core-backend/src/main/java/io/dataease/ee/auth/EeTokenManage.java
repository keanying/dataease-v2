package io.dataease.ee.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.auth.vo.TokenVO;
import io.dataease.exception.DEException;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import io.dataease.utils.RsaUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 登录 token：HS256 JWT，声明 uid/oid，签名密钥由 用户密码摘要 与 系统 RSA 私钥 派生（服务端保密）。
 * 修改密码后旧 token 自动失效。
 */
@Component
public class EeTokenManage {

    /**
     * token 有效期（分钟）
     */
    @Value("${dataease.login_timeout:480}")
    private Long loginTimeout;

    public TokenVO generate(Long uid, Long oid) {
        V3BaseUser user = V3UserUtil.getUser(uid);
        if (user == null) {
            DEException.throwException(ResultCode.USER_NOT_EXIST.code(), ResultCode.USER_NOT_EXIST.message());
        }
        long now = System.currentTimeMillis();
        long exp = now + loginTimeout * 60 * 1000;
        JWTCreator.Builder builder = JWT.create()
                .withClaim("uid", uid)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(exp));
        if (oid != null) {
            builder.withClaim("oid", oid);
        }
        String token = builder.sign(algorithm(user));
        return new TokenVO(token, exp);
    }

    /**
     * 校验 token 并返回 {uid, oid}
     */
    public Long[] verify(String token) {
        if (StringUtils.isBlank(token)) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "token is empty");
        }
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "token is invalid");
            return null;
        }
        Long uid = decoded.getClaim("uid").asLong();
        if (ObjectUtils.isEmpty(uid)) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "token is invalid");
        }
        V3BaseUser user = V3UserUtil.getUser(uid);
        if (user == null) {
            DEException.throwException(ResultCode.USER_NOT_EXIST.code(), ResultCode.USER_NOT_EXIST.message());
        }
        if (!Boolean.TRUE.equals(user.getEnable())) {
            DEException.throwException(ResultCode.USER_ACCOUNT_FORBIDDEN.code(), ResultCode.USER_ACCOUNT_FORBIDDEN.message());
        }
        try {
            JWTVerifier verifier = JWT.require(algorithm(user)).withClaim("uid", uid).build();
            verifier.verify(token);
        } catch (JWTVerificationException e) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), "token is expired or invalid");
        }
        return new Long[]{uid, decoded.getClaim("oid").asLong()};
    }

    public Long getLoginTimeout() {
        return loginTimeout;
    }

    private Algorithm algorithm(V3BaseUser user) {
        return Algorithm.HMAC256(StringUtils.defaultString(user.getPwd()) + RsaUtils.privateKey());
    }
}
