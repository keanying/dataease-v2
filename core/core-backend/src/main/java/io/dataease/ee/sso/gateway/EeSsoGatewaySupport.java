package io.dataease.ee.sso.gateway;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import io.dataease.api.xpack.settings.vo.XpackCasVO;
import io.dataease.api.xpack.settings.vo.XpackOidcVO;
import io.dataease.constant.AuthConstant;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeAuthConfigManage;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.cas.EeCasManage;
import io.dataease.ee.sso.dao.EeAuthConfig;
import io.dataease.ee.sso.oidc.EeOidcManage;
import io.dataease.ee.sso.support.EeSsoKeys;
import io.dataease.ee.sso.support.ExternalIdentity;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC / CAS 网关模式的公共逻辑。
 *
 * <ul>
 *     <li>外部网关（安装包中的 APISIX）：网关完成 OIDC/CAS 认证后注入 {@code X-Userinfo} / {@code X-CAS-USER}，
 *     同时注入共享密钥头 {@code X-DE-GATEWAY-SECRET}，后端只信任携带正确密钥的身份头；</li>
 *     <li>内置网关（未部署 APISIX 或直接访问后端端口）：{@link EeSsoGatewayFilter} 在 {@code /oidcbi/}、{@code /casbi/}
 *     路径下自行完成授权码 / ticket 流程，并以签名 Cookie 保存会话。</li>
 * </ul>
 */
@Component
public class EeSsoGatewaySupport {

    public static final String SESSION_COOKIE = "DE_SSO_SESSION";

    public static final String STATE_COOKIE = "DE_SSO_STATE";

    private static final long CONFIG_TTL = 10_000L;

    private static final long STATE_TTL = 10 * 60 * 1000L;

    private static final int MAX_ID_TOKEN_IN_COOKIE = 2400;

    @Value("${dataease.ee.sso.gateway-secret:}")
    private String gatewaySecret;

    @Value("${dataease.apisix-api.key:}")
    private String apisixKey;

    /**
     * 网关身份头的信任方式：secret（默认，须携带共享密钥）、any（信任任意请求的身份头，仅限网关会剥离客户端同名请求头的部署）、none（不接受身份头）
     */
    @Value("${dataease.ee.sso.gateway-trust:secret}")
    private String gatewayTrust;

    @Value("${dataease.ee.sso.native-enabled:true}")
    private boolean nativeEnabled;

    @Value("${dataease.login_timeout:480}")
    private long loginTimeout;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final EeAuthConfigManage configManage;

    private final EeOidcManage oidcManage;

    private final EeCasManage casManage;

    private final Map<String, CachedConfig> configCache = new ConcurrentHashMap<>();

    public EeSsoGatewaySupport(EeAuthConfigManage configManage, EeOidcManage oidcManage, EeCasManage casManage) {
        this.configManage = configManage;
        this.oidcManage = oidcManage;
        this.casManage = casManage;
    }

    public boolean nativeEnabled() {
        return nativeEnabled;
    }

    public String contextPath() {
        String ctx = StringUtils.trimToEmpty(contextPath);
        if (ctx.equals("/")) {
            return "";
        }
        return StringUtils.removeEnd(ctx, "/");
    }

    /**
     * 网关注入的共享密钥：优先 dataease.ee.sso.gateway-secret，否则由 APISIX 管理密钥派生
     */
    public String gatewaySecret() {
        if (StringUtils.isNotBlank(gatewaySecret)) {
            return gatewaySecret.trim();
        }
        return EeSsoKeys.hmacHex("gateway-secret", StringUtils.defaultString(apisixKey)).substring(0, 40);
    }

    public boolean trustedGateway(HttpServletRequest request) {
        if (StringUtils.equalsIgnoreCase(gatewayTrust, "none")) {
            return false;
        }
        if (StringUtils.equalsIgnoreCase(gatewayTrust, "any")) {
            return true;
        }
        return EeSsoKeys.safeEquals(request.getHeader(EeSsoConstants.GATEWAY_SECRET_HEADER), gatewaySecret());
    }

    /**
     * 解析当前请求携带的 OIDC / CAS 身份：内置网关识别结果 → 受信任网关的身份头 → 内置网关会话 Cookie
     */
    public ExternalIdentity resolve(int origin, HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attribute = request.getAttribute(EeSsoConstants.IDENTITY_ATTRIBUTE);
        if (attribute instanceof ExternalIdentity identity && identity.origin() == origin) {
            return identity;
        }
        ExternalIdentity identity = fromHeaders(origin, request);
        if (identity != null) {
            return identity;
        }
        return fromSession(origin, request);
    }

    public ExternalIdentity fromHeaders(int origin, HttpServletRequest request) {
        if (origin == EeConstants.ORIGIN_OIDC) {
            String header = request.getHeader(AuthConstant.OIDC_X_USER);
            if (StringUtils.isBlank(header) || !trustedGateway(request)) {
                return null;
            }
            JsonNode claims = EeOidcManage.parseUserinfoHeader(header);
            return claims == null ? null : oidcManage.identity(config(EeSsoConstants.OIDC, XpackOidcVO.class), claims);
        }
        if (origin == EeConstants.ORIGIN_CAS) {
            String user = StringUtils.trimToNull(utf8Header(request.getHeader(AuthConstant.CAS_X_USER)));
            if (user == null || !trustedGateway(request)) {
                return null;
            }
            return casManage.identity(user, Map.of());
        }
        return null;
    }

    /**
     * 网关以原始 UTF-8 字节传递请求头，容器按 ISO-8859-1 解码；能按 UTF-8 正确还原时还原
     */
    static String utf8Header(String value) {
        if (value == null || value.chars().allMatch(c -> c < 0x80)) {
            return value;
        }
        if (value.chars().anyMatch(c -> c > 0xff)) {
            return value;
        }
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return value;
        }
    }

    /**
     * 内置网关会话中保存的 OIDC id_token（登出时作为 id_token_hint）
     */
    public String sessionIdToken(HttpServletRequest request) {
        String value = cookie(request, SESSION_COOKIE);
        if (value == null) {
            return null;
        }
        try {
            return JWT.require(algorithm("session")).build().verify(value).getClaim("idt").asString();
        } catch (Exception e) {
            return null;
        }
    }

    public ExternalIdentity fromSession(int origin, HttpServletRequest request) {
        String value = cookie(request, SESSION_COOKIE);
        if (value == null) {
            return null;
        }
        try {
            DecodedJWT jwt = JWT.require(algorithm("session")).withClaim("origin", origin).build().verify(value);
            return new ExternalIdentity(origin, jwt.getSubject(), jwt.getClaim("account").asString(), jwt.getClaim("name").asString(),
                    jwt.getClaim("email").asString(), jwt.getClaim("phone").asString());
        } catch (Exception e) {
            return null;
        }
    }

    public void writeSession(HttpServletRequest request, HttpServletResponse response, String prefix, ExternalIdentity identity) {
        writeSession(request, response, prefix, identity, null);
    }

    /**
     * @param idToken OIDC id_token，登出时作为 id_token_hint（过长时不保存，避免 Cookie 超过浏览器限制）
     */
    public void writeSession(HttpServletRequest request, HttpServletResponse response, String prefix, ExternalIdentity identity, String idToken) {
        long now = System.currentTimeMillis();
        JWTCreator.Builder builder = JWT.create().withClaim("origin", identity.origin()).withSubject(identity.sub())
                .withClaim("account", identity.account()).withClaim("name", identity.name())
                .withClaim("email", identity.email()).withClaim("phone", identity.phone())
                .withIssuedAt(new Date(now)).withExpiresAt(new Date(now + loginTimeout * 60_000L));
        if (idToken != null && idToken.length() <= MAX_ID_TOKEN_IN_COOKIE) {
            builder.withClaim("idt", idToken);
        }
        String value = builder.sign(algorithm("session"));
        addCookie(request, response, SESSION_COOKIE, value, prefix, (int) (loginTimeout * 60));
    }

    public void writeState(HttpServletRequest request, HttpServletResponse response, String prefix, Map<String, String> claims) {
        long now = System.currentTimeMillis();
        JWTCreator.Builder builder = JWT.create().withIssuedAt(new Date(now)).withExpiresAt(new Date(now + STATE_TTL));
        claims.forEach(builder::withClaim);
        addCookie(request, response, STATE_COOKIE, builder.sign(algorithm("state")), prefix, (int) (STATE_TTL / 1000));
    }

    public DecodedJWT readState(HttpServletRequest request) {
        String value = cookie(request, STATE_COOKIE);
        if (value == null) {
            return null;
        }
        try {
            return JWT.require(algorithm("state")).build().verify(value);
        } catch (Exception e) {
            return null;
        }
    }

    public void clear(HttpServletRequest request, HttpServletResponse response, String prefix, String name) {
        addCookie(request, response, name, "", prefix, 0);
    }

    /**
     * 当前站点根地址（含 context path，不含结尾斜杠）
     */
    public String siteRoot(HttpServletRequest request) {
        String proto = StringUtils.defaultIfBlank(firstHeader(request, "X-Forwarded-Proto"), request.getScheme());
        String host = firstHeader(request, "X-Forwarded-Host");
        if (StringUtils.isBlank(host)) {
            host = StringUtils.defaultIfBlank(request.getHeader("Host"), request.getServerName() + ":" + request.getServerPort());
        }
        return proto + "://" + host + contextPath();
    }

    /**
     * 读取配置（带 10 秒缓存，网关前缀下的每个请求都会用到）
     */
    @SuppressWarnings("unchecked")
    public <T> T config(String name, Class<T> type) {
        CachedConfig cached = configCache.get(name);
        long now = System.currentTimeMillis();
        if (cached == null || cached.loadedAt + CONFIG_TTL < now) {
            EeAuthConfig entity = configManage.find(name);
            Object config = configManage.parse(entity, type);
            boolean active = entity != null && Boolean.TRUE.equals(entity.getEnable());
            cached = new CachedConfig(config, active, now);
            configCache.put(name, cached);
        }
        return (T) cached.config;
    }

    public boolean active(String name, Class<?> type) {
        config(name, type);
        CachedConfig cached = configCache.get(name);
        return cached != null && cached.active && cached.config != null;
    }

    public void evict(String name) {
        configCache.remove(name);
    }

    public XpackOidcVO oidc() {
        return config(EeSsoConstants.OIDC, XpackOidcVO.class);
    }

    public XpackCasVO cas() {
        return config(EeSsoConstants.CAS, XpackCasVO.class);
    }

    private void addCookie(HttpServletRequest request, HttpServletResponse response, String name, String value, String prefix, int maxAge) {
        String path = contextPath() + prefix;
        boolean secure = request.isSecure() || StringUtils.equalsIgnoreCase(firstHeader(request, "X-Forwarded-Proto"), "https");
        StringBuilder header = new StringBuilder();
        header.append(name).append('=').append(StringUtils.defaultString(value))
                .append("; Path=").append(path)
                .append("; Max-Age=").append(Math.max(0, maxAge))
                .append("; HttpOnly; SameSite=Lax");
        if (secure) {
            header.append("; Secure");
        }
        response.addHeader("Set-Cookie", header.toString());
    }

    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && StringUtils.isNotBlank(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return StringUtils.trim(StringUtils.split(value, ',')[0]);
    }

    private Algorithm algorithm(String purpose) {
        return Algorithm.HMAC256(EeSsoKeys.derive("gateway-" + purpose));
    }

    private record CachedConfig(Object config, boolean active, long loadedAt) {
    }
}
