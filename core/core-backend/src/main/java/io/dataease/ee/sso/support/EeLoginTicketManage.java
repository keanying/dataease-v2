package io.dataease.ee.sso.support;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 第三方登录临时票据：钉钉/飞书/企业微信 {@code /token}、OAuth2 {@code /oauth2/token}、SAML2 ACS 换取的短期 JWT。
 *
 * <p>前端把票据作为 X-DE-TOKEN 调用 {@code /login/platformLogin/{origin}}，后端校验来源一致、未过期且仅使用一次，
 * 再换发正式登录 token。票据签名密钥与登录 token 不同，不能直接作为登录凭证使用。</p>
 */
@Component
public class EeLoginTicketManage {

    private static final String TYPE = "de-login-ticket";

    private final EeTtlStore ttlStore;

    public EeLoginTicketManage(EeTtlStore ttlStore) {
        this.ttlStore = ttlStore;
    }

    public String issue(ExternalIdentity identity) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("typ", TYPE)
                .withClaim("origin", identity.origin())
                .withSubject(identity.sub())
                .withClaim("account", identity.account())
                .withClaim("name", identity.name())
                .withClaim("email", identity.email())
                .withClaim("phone", identity.phone())
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + TimeUnit.MINUTES.toMillis(EeSsoConstants.TICKET_TTL_MINUTES)))
                .sign(algorithm());
    }

    /**
     * 校验并消费票据
     */
    public ExternalIdentity consume(int origin, String ticket) {
        if (StringUtils.isBlank(ticket) || StringUtils.equals(ticket, "null")) {
            DEException.throwException(Translator.get("ee_sso_ticket_invalid"));
        }
        DecodedJWT decoded = null;
        try {
            decoded = JWT.require(algorithm()).withClaim("typ", TYPE).build().verify(ticket);
        } catch (JWTVerificationException e) {
            DEException.throwException(Translator.get("ee_sso_ticket_invalid"));
        }
        Integer ticketOrigin = decoded.getClaim("origin").asInt();
        if (ticketOrigin == null || ticketOrigin != origin) {
            DEException.throwException(Translator.get("ee_sso_ticket_invalid"));
        }
        if (!ttlStore.markOnce(EeSsoConstants.CACHE_TICKET_USED, decoded.getId(), EeSsoConstants.TICKET_TTL_MINUTES + 1)) {
            DEException.throwException(Translator.get("ee_sso_ticket_invalid"));
        }
        return new ExternalIdentity(origin, decoded.getSubject(), decoded.getClaim("account").asString(),
                decoded.getClaim("name").asString(), decoded.getClaim("email").asString(), decoded.getClaim("phone").asString());
    }

    private Algorithm algorithm() {
        return Algorithm.HMAC256(EeSsoKeys.derive("login-ticket"));
    }
}
