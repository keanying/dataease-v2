package io.dataease.ee.sso.oauth2;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import io.dataease.api.xpack.settings.request.XpackOauth2TokenRequest;
import io.dataease.api.xpack.settings.vo.XpackOauth2VO;
import io.dataease.api.xpack.settings.vo.XpackOauthAuthVO;
import io.dataease.api.xpack.settings.vo.XpackOauthTokenVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OAuth2 授权码登录：生成带签名的 state，换取 access_token 后读取用户信息并签发临时登录票据。
 */
@Component
public class EeOauth2Manage {

    private static final String STATE_PREFIX = "fit2clouddeoauth2";

    private static final long STATE_TTL = EeSsoConstants.TICKET_TTL_MINUTES * 60 * 1000L;

    private final EeAuthConfigManage configManage;

    private final EeHttp http;

    private final EeTtlStore ttlStore;

    private final EeLoginTicketManage ticketManage;

    public EeOauth2Manage(EeAuthConfigManage configManage, EeHttp http, EeTtlStore ttlStore, EeLoginTicketManage ticketManage) {
        this.configManage = configManage;
        this.http = http;
        this.ttlStore = ttlStore;
        this.ticketManage = ticketManage;
    }

    public String validate(XpackOauth2VO vo) {
        try {
            if (vo == null || StringUtils.isAnyBlank(vo.getClientId(), vo.getClientSecret(), vo.getAuthEndpoint(), vo.getTokenEndpoint(),
                    vo.getUserInfoEndpoint(), vo.getRedirectUri())) {
                return Translator.get("ee_sso_config_incomplete");
            }
            EeJson.mapping(vo.getMapping());
            for (String url : List.of(vo.getAuthEndpoint(), vo.getTokenEndpoint(), vo.getUserInfoEndpoint(), vo.getRedirectUri())) {
                URI uri = URI.create(url.trim());
                if (!StringUtils.equalsAnyIgnoreCase(uri.getScheme(), "http", "https") || StringUtils.isBlank(uri.getHost())) {
                    return Translator.get("ee_sso_url_invalid", url);
                }
            }
            EeHttp.Response response = http.get(vo.getAuthEndpoint().trim(), null);
            if (response.status() >= 500 || response.status() == 404) {
                return "OAuth2 authorization endpoint HTTP " + response.status();
            }
            return "true";
        } catch (IllegalArgumentException e) {
            return Translator.get("ee_sso_mapping_invalid") + ": " + e.getMessage();
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
        }
    }

    public XpackOauthAuthVO auth() {
        XpackOauth2VO vo = configManage.requireActive(EeSsoConstants.OAUTH2, XpackOauth2VO.class);
        XpackOauthAuthVO result = new XpackOauthAuthVO();
        result.setState(newState());
        result.setClientId(vo.getClientId());
        result.setRedirectUri(vo.getRedirectUri());
        result.setAuthEndpoint(vo.getAuthEndpoint());
        result.setScope(EeHttp.encode(StringUtils.defaultIfBlank(vo.getScope(), "")));
        result.setCodeKey(codeKey(vo));
        return result;
    }

    public XpackOauthTokenVO token(XpackOauth2TokenRequest request) {
        XpackOauth2VO vo = configManage.requireActive(EeSsoConstants.OAUTH2, XpackOauth2VO.class);
        if (request == null || StringUtils.isAnyBlank(request.getCode(), request.getState())) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
        checkState(request.getState());
        JsonNode token;
        try {
            token = exchange(vo, request.getCode());
        } catch (Exception e) {
            LogUtil.error("oauth2 token exchange failed: " + e.getMessage());
            DEException.throwException(Translator.get("ee_sso_remote_failed", e.getMessage()));
            return null;
        }
        String accessToken = EeJson.text(token, "access_token");
        if (StringUtils.isBlank(accessToken)) {
            DEException.throwException(Translator.get("ee_sso_remote_failed", StringUtils.abbreviate(token.toString(), 200)));
        }
        JsonNode info;
        try {
            info = http.get(vo.getUserInfoEndpoint().trim(), EeHttp.headers("Authorization", "Bearer " + accessToken))
                    .require("OAuth2 userinfo").json();
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_sso_remote_failed", e.getMessage()));
            return null;
        }
        ExternalIdentity identity = identity(vo, info);
        XpackOauthTokenVO result = new XpackOauthTokenVO();
        result.setToken(ticketManage.issue(identity));
        result.setIdToken(EeJson.text(token, "id_token"));
        return result;
    }

    JsonNode exchange(XpackOauth2VO vo, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", vo.getRedirectUri().trim());
        form.put("client_id", vo.getClientId());
        Map<String, String> headers = new LinkedHashMap<>();
        if (StringUtils.equals(vo.getAuthMethod(), "1")) {
            // client_secret_jwt（RFC 7523）
            long now = System.currentTimeMillis();
            String assertion = JWT.create().withIssuer(vo.getClientId()).withSubject(vo.getClientId())
                    .withAudience(vo.getTokenEndpoint().trim()).withJWTId(UUID.randomUUID().toString())
                    .withIssuedAt(new Date(now)).withExpiresAt(new Date(now + 5 * 60_000L))
                    .sign(Algorithm.HMAC256(vo.getClientSecret()));
            form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            form.put("client_assertion", assertion);
        } else {
            form.put("client_secret", vo.getClientSecret());
        }
        headers.put("Accept", "application/json");
        EeHttp.Response response = http.postForm(vo.getTokenEndpoint().trim(), form, headers).require("OAuth2 token");
        String body = StringUtils.trimToEmpty(response.body());
        if (body.startsWith("{")) {
            return response.json();
        }
        // 兼容以 application/x-www-form-urlencoded 返回的令牌端点（如 GitHub 旧接口）
        var node = EeJson.object();
        for (String pair : StringUtils.split(body, '&')) {
            String[] kv = pair.split("=", 2);
            node.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return node;
    }

    ExternalIdentity identity(XpackOauth2VO vo, JsonNode info) {
        Map<String, String> mapping = EeJson.mapping(vo.getMapping());
        String account = first(info, mapping.get("account"), "account", "username", "login", "preferred_username", "sub", "id");
        if (StringUtils.isBlank(account)) {
            DEException.throwException(Translator.get("ee_sso_identity_missing"));
        }
        String sub = StringUtils.defaultIfBlank(first(info, mapping.get("sub"), mapping.get("account")), account);
        String name = first(info, mapping.get("name"), "name", "nickname", "displayName");
        String email = first(info, mapping.get("email"), "email", "mail");
        String phone = first(info, mapping.get("phone"), "phone", "mobile", "phone_number");
        return new ExternalIdentity(EeConstants.ORIGIN_OAUTH2, sub, account, name, email, phone);
    }

    private String codeKey(XpackOauth2VO vo) {
        try {
            String key = EeJson.mapping(vo.getMapping()).get("codeKey");
            return StringUtils.defaultIfBlank(key, "code");
        } catch (Exception e) {
            return "code";
        }
    }

    private String newState() {
        String nonce = EeSsoKeys.randomHex(12);
        String exp = Long.toHexString(System.currentTimeMillis() + STATE_TTL);
        String payload = nonce + exp;
        return STATE_PREFIX + payload + EeSsoKeys.hmacHex("oauth2-state", payload).substring(0, 24);
    }

    private void checkState(String state) {
        if (!state.startsWith(STATE_PREFIX) || state.length() <= STATE_PREFIX.length() + 24 + 24) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
        String body = state.substring(STATE_PREFIX.length());
        String payload = body.substring(0, body.length() - 24);
        String sig = body.substring(body.length() - 24);
        if (!EeSsoKeys.safeEquals(sig, EeSsoKeys.hmacHex("oauth2-state", payload).substring(0, 24))) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
        long exp;
        try {
            exp = Long.parseLong(payload.substring(24), 16);
        } catch (NumberFormatException e) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
            return;
        }
        if (exp < System.currentTimeMillis() || !ttlStore.markOnce(EeSsoConstants.CACHE_TICKET_USED, "oauth2:" + payload, EeSsoConstants.TICKET_TTL_MINUTES + 1)) {
            DEException.throwException(Translator.get("ee_sso_state_invalid"));
        }
    }

    private String first(JsonNode node, String... paths) {
        for (String path : paths) {
            String value = EeJson.text(node, path);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
