package io.dataease.ee.sso.gateway;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.xpack.settings.vo.XpackCasVO;
import io.dataease.api.xpack.settings.vo.XpackOidcVO;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.EeSsoKeys;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 维护安装包中 APISIX 网关的路由（Admin API，{@code dataease.apisix-api.domain} / {@code dataease.apisix-api.key}）：
 * <ul>
 *     <li>{@code dataease}：{@code /*} 转发到 DataEase；</li>
 *     <li>{@code dataease-oidc}：{@code /oidcbi/*}，openid-connect 插件认证后注入 X-Userinfo；</li>
 *     <li>{@code dataease-cas}：{@code /casbi/*}，cas-auth 插件认证后注入 X-CAS-USER。</li>
 * </ul>
 * 两个认证路由都会去掉前缀并注入共享密钥头 X-DE-GATEWAY-SECRET。网关不可用时只记录日志，不影响保存（内置网关仍可工作）。
 */
@Component
public class EeApisixRouteManage {

    private static final String ROUTE_DEFAULT = "dataease";

    private static final String ROUTE_OIDC = "dataease-oidc";

    private static final String ROUTE_CAS = "dataease-cas";

    @Value("${dataease.apisix-api.domain:}")
    private String domain;

    @Value("${dataease.apisix-api.key:}")
    private String key;

    @Value("${dataease.ee.sso.apisix.enabled:true}")
    private boolean enabled;

    @Value("${dataease.ee.sso.apisix.upstream:dataease:8100}")
    private String upstream;

    private final EeHttp http;

    private final EeSsoGatewaySupport support;

    public EeApisixRouteManage(EeHttp http, EeSsoGatewaySupport support) {
        this.http = http;
        this.support = support;
    }

    public void syncOidc(XpackOidcVO vo, boolean enable) {
        if (!usable()) {
            return;
        }
        if (!enable || vo == null) {
            delete(ROUTE_OIDC);
            return;
        }
        String ctx = support.contextPath();
        ObjectNode oidc = EeJson.object();
        oidc.put("client_id", vo.getClientId());
        oidc.put("client_secret", vo.getClientSecret());
        oidc.put("discovery", vo.getDiscovery());
        if (StringUtils.isNotBlank(vo.getRealm())) {
            oidc.put("realm", vo.getRealm());
        }
        oidc.put("scope", StringUtils.defaultIfBlank(vo.getScope(), "openid profile email"));
        oidc.put("bearer_only", false);
        oidc.put("use_pkce", vo.isUsePkce());
        oidc.put("redirect_uri", vo.getRedirectUri());
        oidc.put("set_userinfo_header", true);
        oidc.put("set_id_token_header", false);
        oidc.put("set_access_token_header", false);
        oidc.put("logout_path", ctx + EeSsoConstants.OIDC_LOGOUT_PATH);
        String root = siteRoot(vo.getRedirectUri());
        if (root != null) {
            oidc.put("post_logout_redirect_uri", root + ctx + "/");
        }
        oidc.putObject("session").put("secret", EeSsoKeys.hmacHex("apisix-session", key).substring(0, 32));
        ObjectNode plugins = EeJson.object();
        plugins.set("openid-connect", oidc);
        plugins.set("proxy-rewrite", rewrite(EeSsoConstants.OIDC_PREFIX));
        put(ROUTE_OIDC, route(ROUTE_OIDC, ctx + EeSsoConstants.OIDC_PREFIX + "/*", 10, plugins));
        put(ROUTE_DEFAULT, route(ROUTE_DEFAULT, "/*", 0, null));
    }

    public void syncCas(XpackCasVO vo, boolean enable) {
        if (!usable()) {
            return;
        }
        if (!enable || vo == null) {
            delete(ROUTE_CAS);
            return;
        }
        String ctx = support.contextPath();
        ObjectNode cas = EeJson.object();
        cas.put("idp_uri", StringUtils.removeEnd(vo.getIdpUri().trim(), "/"));
        cas.put("cas_callback_uri", ctx + EeSsoConstants.CAS_CALLBACK_PATH);
        cas.put("logout_uri", ctx + EeSsoConstants.CAS_LOGOUT_PATH);
        if (StringUtils.isNotBlank(vo.getCasCallbackDomain())) {
            cas.put("cas_callback_domain", StringUtils.removeEnd(vo.getCasCallbackDomain().trim(), "/"));
        }
        if (StringUtils.isNotBlank(vo.getLogoutRedirectUrl())) {
            cas.put("logout_redirect_url", vo.getLogoutRedirectUrl().trim());
        }
        ObjectNode plugins = EeJson.object();
        plugins.set("cas-auth", cas);
        plugins.set("proxy-rewrite", rewrite(EeSsoConstants.CAS_PREFIX));
        put(ROUTE_CAS, route(ROUTE_CAS, ctx + EeSsoConstants.CAS_PREFIX + "/*", 10, plugins));
        put(ROUTE_DEFAULT, route(ROUTE_DEFAULT, "/*", 0, null));
    }

    private ObjectNode rewrite(String prefix) {
        ObjectNode rewrite = EeJson.object();
        ArrayNode regex = rewrite.putArray("regex_uri");
        regex.add("^(.*)" + prefix + "/(.*)$");
        regex.add("$1/$2");
        rewrite.putObject("headers").putObject("set").put(EeSsoConstants.GATEWAY_SECRET_HEADER, support.gatewaySecret());
        return rewrite;
    }

    private ObjectNode route(String name, String uri, int priority, ObjectNode plugins) {
        ObjectNode route = EeJson.object();
        route.put("name", name);
        route.put("uri", uri);
        route.put("priority", priority);
        if (plugins != null) {
            route.set("plugins", plugins);
        }
        ObjectNode up = route.putObject("upstream");
        up.put("type", "roundrobin");
        up.putObject("nodes").put(upstream, 1);
        return route;
    }

    private boolean usable() {
        return enabled && StringUtils.isNotBlank(domain) && StringUtils.isNotBlank(key);
    }

    private void put(String id, ObjectNode body) {
        try {
            EeHttp.Response response = http.putJson(base() + id, body, EeHttp.headers("X-API-KEY", key));
            if (!response.ok()) {
                LogUtil.warn("apisix route " + id + " not saved: HTTP " + response.status() + " " + StringUtils.abbreviate(response.body(), 300));
            }
        } catch (Exception e) {
            LogUtil.warn("apisix unavailable, route " + id + " not saved: " + e.getMessage());
        }
    }

    private void delete(String id) {
        try {
            http.delete(base() + id, EeHttp.headers("X-API-KEY", key));
        } catch (Exception e) {
            LogUtil.warn("apisix unavailable, route " + id + " not deleted: " + e.getMessage());
        }
    }

    private String base() {
        return StringUtils.removeEnd(domain.trim(), "/") + "/apisix/admin/routes/";
    }

    private String siteRoot(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (Exception e) {
            return null;
        }
    }
}
