package io.dataease.ee.sso.oidc;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import io.dataease.api.xpack.settings.vo.XpackOidcVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC：配置校验、身份映射，以及内置网关模式下的授权码流程（发现文档、授权地址、换取令牌、用户信息）。
 */
@Component
public class EeOidcManage {

    private static final long DISCOVERY_TTL = 10 * 60 * 1000L;

    private final EeHttp http;

    private final Map<String, Discovery> discoveryCache = new ConcurrentHashMap<>();

    public EeOidcManage(EeHttp http) {
        this.http = http;
    }

    public String validate(XpackOidcVO vo) {
        try {
            if (vo == null || StringUtils.isAnyBlank(vo.getClientId(), vo.getClientSecret(), vo.getDiscovery(), vo.getRedirectUri())) {
                return Translator.get("ee_sso_config_incomplete");
            }
            EeJson.mapping(vo.getMapping());
            String redirectPath = URI.create(vo.getRedirectUri().trim()).getPath();
            if (redirectPath == null || !redirectPath.contains(EeSsoConstants.OIDC_PREFIX + "/")) {
                return Translator.get("ee_oidc_redirect_invalid");
            }
            Discovery discovery = discovery(vo.getDiscovery(), true);
            if (StringUtils.isAnyBlank(discovery.authorizationEndpoint, discovery.tokenEndpoint)) {
                return Translator.get("ee_oidc_discovery_invalid");
            }
            return "true";
        } catch (IllegalArgumentException e) {
            return Translator.get("ee_sso_mapping_invalid") + ": " + e.getMessage();
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    public Discovery discovery(String url, boolean refresh) {
        String key = StringUtils.trim(url);
        Discovery cached = discoveryCache.get(key);
        if (!refresh && cached != null && cached.loadedAt + DISCOVERY_TTL > System.currentTimeMillis()) {
            return cached;
        }
        JsonNode json = http.get(key, null).require("OIDC discovery").json();
        Discovery discovery = new Discovery(EeJson.text(json, "issuer"), EeJson.text(json, "authorization_endpoint"),
                EeJson.text(json, "token_endpoint"), EeJson.text(json, "userinfo_endpoint"), EeJson.text(json, "end_session_endpoint"),
                System.currentTimeMillis());
        discoveryCache.put(key, discovery);
        return discovery;
    }

    /**
     * 构造授权地址
     */
    public String authorizeUrl(XpackOidcVO vo, String state, String nonce, String verifier) {
        Discovery discovery = discovery(vo.getDiscovery(), false);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", vo.getClientId());
        query.put("redirect_uri", vo.getRedirectUri().trim());
        query.put("scope", StringUtils.defaultIfBlank(vo.getScope(), "openid profile email"));
        query.put("state", state);
        query.put("nonce", nonce);
        if (vo.isUsePkce() && verifier != null) {
            query.put("code_challenge", challenge(verifier));
            query.put("code_challenge_method", "S256");
        }
        return EeHttp.url(discovery.authorizationEndpoint, query);
    }

    /**
     * 授权码换取用户声明（userinfo 优先，合并 id_token 声明）
     */
    public JsonNode exchange(XpackOidcVO vo, String code, String nonce, String verifier) {
        return exchangeTokens(vo, code, nonce, verifier).claims();
    }

    /**
     * 授权码换取用户声明与 id_token
     */
    public TokenResult exchangeTokens(XpackOidcVO vo, String code, String nonce, String verifier) {
        Discovery discovery = discovery(vo.getDiscovery(), false);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", vo.getRedirectUri().trim());
        form.put("client_id", vo.getClientId());
        form.put("client_secret", vo.getClientSecret());
        if (vo.isUsePkce() && verifier != null) {
            form.put("code_verifier", verifier);
        }
        JsonNode token = http.postForm(discovery.tokenEndpoint, form, null).require("OIDC token").json();
        String accessToken = EeJson.text(token, "access_token");
        String idToken = EeJson.text(token, "id_token");
        var claims = EeJson.object();
        if (StringUtils.isNotBlank(idToken)) {
            // id_token 由令牌端点经 TLS 直接返回，按 OIDC Core 3.1.3.7 可用 TLS 校验代替签名校验；仍校验 nonce 与受众
            DecodedJWT decoded = JWT.decode(idToken);
            String tokenNonce = decoded.getClaim("nonce").asString();
            if (tokenNonce != null && !StringUtils.equals(tokenNonce, nonce)) {
                throw new IllegalStateException("OIDC nonce mismatch");
            }
            if (decoded.getAudience() != null && !decoded.getAudience().isEmpty() && !decoded.getAudience().contains(vo.getClientId())) {
                throw new IllegalStateException("OIDC audience mismatch");
            }
            JsonNode payload = EeJson.tree(new String(Base64.getUrlDecoder().decode(decoded.getPayload()), StandardCharsets.UTF_8));
            payload.fields().forEachRemaining(e -> claims.set(e.getKey(), e.getValue()));
        }
        if (StringUtils.isNotBlank(accessToken) && StringUtils.isNotBlank(discovery.userinfoEndpoint)) {
            JsonNode info = http.get(discovery.userinfoEndpoint, EeHttp.headers("Authorization", "Bearer " + accessToken))
                    .require("OIDC userinfo").json();
            info.fields().forEachRemaining(e -> claims.set(e.getKey(), e.getValue()));
        }
        if (claims.isEmpty()) {
            throw new IllegalStateException("OIDC provider returned no user claims");
        }
        return new TokenResult(claims, idToken);
    }

    public record TokenResult(JsonNode claims, String idToken) {
    }

    /**
     * 按映射配置把 OIDC 声明转换为外部身份
     */
    public ExternalIdentity identity(XpackOidcVO vo, JsonNode claims) {
        Map<String, String> mapping = vo == null ? Map.of() : safeMapping(vo.getMapping());
        String account = first(claims, mapping.get("account"), "preferred_username", "username", "sub");
        String name = first(claims, mapping.get("name"), "name", "nickname", "preferred_username");
        String email = first(claims, mapping.get("email"), "email");
        String phone = first(claims, mapping.get("phone"), "phone_number");
        if (StringUtils.isBlank(account)) {
            return null;
        }
        return new ExternalIdentity(EeConstants.ORIGIN_OIDC, account, account, name, email, phone);
    }

    /**
     * 解析网关注入的 X-Userinfo（APISIX openid-connect 插件为 Base64 编码的 JSON）
     */
    public static JsonNode parseUserinfoHeader(String header) {
        if (StringUtils.isBlank(header)) {
            return null;
        }
        String value = header.trim();
        if (!value.startsWith("{")) {
            try {
                value = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                try {
                    value = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        try {
            JsonNode node = EeJson.tree(value);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> safeMapping(String json) {
        try {
            return EeJson.mapping(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String first(JsonNode claims, String... paths) {
        for (String path : paths) {
            String value = EeJson.text(claims, path);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint,
                            String endSessionEndpoint, long loadedAt) {
    }
}
