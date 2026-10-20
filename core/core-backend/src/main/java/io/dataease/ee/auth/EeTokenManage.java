package io.dataease.ee.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.auth.vo.TokenVO;
import io.dataease.exception.DEException;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import io.dataease.utils.Md5Utils;
import io.dataease.utils.RsaUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 token：HS256 JWT，声明 uid/oid，签名密钥由 用户密码摘要 与 系统 RSA 私钥 派生（服务端保密），
 * 修改密码后旧 token 自动失效；登出后 token 进入注销名单直至过期。
 *
 * <p>校验失败的提示与前端登录页约定一致：token is empty / token is Expired / token is destroyed /
 * pwd has been changed / user_disable。</p>
 */
@Component
public class EeTokenManage {

    /**
     * token 有效期（分钟）
     */
    @Value("${dataease.login_timeout:480}")
    private Long loginTimeout;

    /**
     * 已注销 token 的摘要 -> 过期时间
     */
    private final Map<String, Long> destroyed = new ConcurrentHashMap<>();

    public TokenVO generate(Long uid, Long oid) {
        V3BaseUser user = V3UserUtil.getUser(uid);
        if (user == null) {
            DEException.throwException(ResultCode.USER_NOT_EXIST.code(), ResultCode.USER_NOT_EXIST.message());
        }
        long now = System.currentTimeMillis();
        long exp = now + loginTimeout * 60 * 1000;
        JWTCreator.Builder builder = JWT.create()
                .withJWTId(UUID.randomUUID().toString())
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
        if (StringUtils.isBlank(token) || StringUtils.equals(token, "null")) {
            fail("token is empty");
        }
        DecodedJWT decoded = null;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            fail("token is invalid");
        }
        Long uid = decoded.getClaim("uid").asLong();
        if (ObjectUtils.isEmpty(uid)) {
            fail("token is invalid");
        }
        if (destroyed.containsKey(digest(token))) {
            fail("token is destroyed");
        }
        V3BaseUser user = V3UserUtil.getUser(uid);
        if (user == null) {
            fail("user_disable: user not exist");
        }
        if (!Boolean.TRUE.equals(user.getEnable())) {
            fail("user_disable");
        }
        Algorithm algorithm = algorithm(user);
        try {
            algorithm.verify(decoded);
        } catch (SignatureVerificationException e) {
            fail("pwd has been changed");
        }
        try {
            JWT.require(algorithm).withClaim("uid", uid).build().verify(token);
        } catch (JWTVerificationException e) {
            fail("token is Expired");
        }
        return new Long[]{uid, decoded.getClaim("oid").asLong()};
    }

    /**
     * 注销 token
     */
    public void destroy(String token) {
        if (StringUtils.isBlank(token)) {
            return;
        }
        long now = System.currentTimeMillis();
        destroyed.entrySet().removeIf(entry -> entry.getValue() < now);
        long exp = now + loginTimeout * 60 * 1000;
        try {
            Date expiresAt = JWT.decode(token).getExpiresAt();
            if (expiresAt != null) {
                exp = expiresAt.getTime();
            }
        } catch (JWTVerificationException ignored) {
            // 无法解析的 token 按默认有效期记录
        }
        destroyed.put(digest(token), exp);
    }

    public Long getLoginTimeout() {
        return loginTimeout;
    }

    private String digest(String token) {
        return Md5Utils.md5(token);
    }

    private void fail(String message) {
        DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), message);
    }

    private Algorithm algorithm(V3BaseUser user) {
        return Algorithm.HMAC256(StringUtils.defaultString(user.getPwd()) + RsaUtils.privateKey());
    }
}
