package io.dataease.ee.sso.gateway;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import io.dataease.api.xpack.settings.vo.XpackCasVO;
import io.dataease.api.xpack.settings.vo.XpackOidcVO;
import io.dataease.constant.AuthConstant;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.cas.EeCasManage;
import io.dataease.ee.sso.oidc.EeOidcManage;
import io.dataease.ee.sso.support.EeSsoKeys;
import io.dataease.ee.sso.support.EeXml;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.utils.LogUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /oidcbi/**}、{@code /casbi/**} 前缀处理（在身份过滤器之前执行）：
 * <ol>
 *     <li>去掉前缀后交给后续过滤器与 MVC（页面、静态资源、{@code /de2api} 接口照常处理）；</li>
 *     <li>受信任网关注入的身份直接使用；</li>
 *     <li>未部署网关时作为内置网关完成 OIDC 授权码 / CAS ticket 流程（{@code dataease.ee.sso.native-enabled}）。</li>
 * </ol>
 */
public class EeSsoGatewayFilter implements Filter {

    private final EeSsoGatewaySupport support;

    private final EeOidcManage oidcManage;

    private final EeCasManage casManage;

    public EeSsoGatewayFilter(EeSsoGatewaySupport support, EeOidcManage oidcManage, EeCasManage casManage) {
        this.support = support;
        this.oidcManage = oidcManage;
        this.casManage = casManage;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String path = StringUtils.removeStart(request.getRequestURI(), ctx);
        String prefix = prefixOf(path);
        if (prefix == null) {
            chain.doFilter(request, response);
            return;
        }
        int origin = EeSsoConstants.OIDC_PREFIX.equals(prefix) ? EeConstants.ORIGIN_OIDC : EeConstants.ORIGIN_CAS;
        String stripped = StringUtils.defaultIfEmpty(path.substring(prefix.length()), "/");
        try {
            if (handled(request, response, prefix, origin, path, stripped)) {
                return;
            }
        } catch (Exception e) {
            LogUtil.error("sso gateway error: " + e.getMessage(), e);
            error(response, e.getMessage());
            return;
        }
        chain.doFilter(new StrippedRequest(request, ctx, stripped), response);
    }

    /**
     * 处理登出、回调、发起认证；返回 true 表示已响应
     */
    private boolean handled(HttpServletRequest request, HttpServletResponse response, String prefix, int origin, String path,
                            String stripped) throws IOException {
        ExternalIdentity headerIdentity = support.fromHeaders(origin, request);
        if (headerIdentity != null) {
            request.setAttribute(EeSsoConstants.IDENTITY_ATTRIBUTE, headerIdentity);
            return false;
        }
        if (!support.nativeEnabled()) {
            return false;
        }
        if (origin == EeConstants.ORIGIN_OIDC) {
            return handleOidc(request, response, prefix, path, stripped);
        }
        return handleCas(request, response, prefix, path, stripped);
    }

    private boolean handleOidc(HttpServletRequest request, HttpServletResponse response, String prefix, String path, String stripped) throws IOException {
        XpackOidcVO vo = support.oidc();
        boolean active = support.active(EeSsoConstants.OIDC, XpackOidcVO.class);
        if (EeSsoConstants.OIDC_LOGOUT_PATH.equals(path)) {
            support.clear(request, response, prefix, EeSsoGatewaySupport.SESSION_COOKIE);
            String target = support.siteRoot(request) + "/";
            if (active && vo != null) {
                try {
                    EeOidcManage.Discovery discovery = oidcManage.discovery(vo.getDiscovery(), false);
                    if (StringUtils.isNotBlank(discovery.endSessionEndpoint())) {
                        Map<String, String> query = new LinkedHashMap<>();
                        query.put("client_id", vo.getClientId());
                        query.put("post_logout_redirect_uri", target);
                        target = io.dataease.ee.sso.support.EeHttp.url(discovery.endSessionEndpoint(), query);
                    }
                } catch (Exception e) {
                    LogUtil.warn("oidc end session endpoint unavailable: " + e.getMessage());
                }
            }
            response.sendRedirect(target);
            return true;
        }
        if (!active) {
            return false;
        }
        String state = request.getParameter("state");
        if (StringUtils.isNotBlank(state) && (request.getParameter("code") != null || request.getParameter("error") != null)) {
            DecodedJWT saved = support.readState(request);
            if (saved != null && EeSsoKeys.safeEquals(saved.getClaim("state").asString(), state)) {
                support.clear(request, response, prefix, EeSsoGatewaySupport.STATE_COOKIE);
                if (request.getParameter("error") != null) {
                    error(response, "OIDC: " + request.getParameter("error") + " " + StringUtils.defaultString(request.getParameter("error_description")));
                    return true;
                }
                JsonNode claims = oidcManage.exchange(vo, request.getParameter("code"), saved.getClaim("nonce").asString(),
                        saved.getClaim("verifier").asString());
                ExternalIdentity identity = oidcManage.identity(vo, claims);
                if (identity == null) {
                    error(response, "OIDC: no account claim");
                    return true;
                }
                support.writeSession(request, response, prefix, identity);
                response.sendRedirect(safeReturn(saved.getClaim("ret").asString(), prefix));
                return true;
            }
        }
        ExternalIdentity session = support.fromSession(EeConstants.ORIGIN_OIDC, request);
        if (session != null) {
            request.setAttribute(EeSsoConstants.IDENTITY_ATTRIBUTE, session);
            return false;
        }
        if (isPage(request, stripped)) {
            String newState = EeSsoKeys.randomHex(16);
            String nonce = EeSsoKeys.randomHex(16);
            String verifier = vo.isUsePkce() ? EeSsoKeys.randomHex(32) : null;
            Map<String, String> claims = new LinkedHashMap<>();
            claims.put("state", newState);
            claims.put("nonce", nonce);
            if (verifier != null) {
                claims.put("verifier", verifier);
            }
            claims.put("ret", currentUri(request));
            support.writeState(request, response, prefix, claims);
            response.sendRedirect(oidcManage.authorizeUrl(vo, newState, nonce, verifier));
            return true;
        }
        return false;
    }

    private boolean handleCas(HttpServletRequest request, HttpServletResponse response, String prefix, String path, String stripped) throws IOException {
        XpackCasVO vo = support.cas();
        boolean active = support.active(EeSsoConstants.CAS, XpackCasVO.class);
        if (EeSsoConstants.CAS_LOGOUT_PATH.equals(path)) {
            support.clear(request, response, prefix, EeSsoGatewaySupport.SESSION_COOKIE);
            response.sendRedirect(active && vo != null ? casManage.logoutUrl(vo) : support.siteRoot(request) + "/");
            return true;
        }
        if (!active) {
            return false;
        }
        String service = serviceUrl(request, vo);
        if (EeSsoConstants.CAS_CALLBACK_PATH.equals(path) && StringUtils.isNotBlank(request.getParameter("ticket"))) {
            DecodedJWT saved = support.readState(request);
            ExternalIdentity identity = casManage.validateTicket(vo, request.getParameter("ticket"), service);
            support.clear(request, response, prefix, EeSsoGatewaySupport.STATE_COOKIE);
            support.writeSession(request, response, prefix, identity);
            response.sendRedirect(safeReturn(saved == null ? null : saved.getClaim("ret").asString(), prefix));
            return true;
        }
        ExternalIdentity session = support.fromSession(EeConstants.ORIGIN_CAS, request);
        if (session != null) {
            request.setAttribute(EeSsoConstants.IDENTITY_ATTRIBUTE, session);
            return false;
        }
        if (isPage(request, stripped)) {
            support.writeState(request, response, prefix, Map.of("ret", currentUri(request)));
            response.sendRedirect(casManage.loginUrl(vo, service));
            return true;
        }
        return false;
    }

    private String serviceUrl(HttpServletRequest request, XpackCasVO vo) {
        String base = StringUtils.isNotBlank(vo.getCasCallbackDomain())
                ? StringUtils.removeEnd(vo.getCasCallbackDomain().trim(), "/") + support.contextPath()
                : support.siteRoot(request);
        return base + EeSsoConstants.CAS_CALLBACK_PATH;
    }

    private String prefixOf(String path) {
        for (String prefix : new String[]{EeSsoConstants.OIDC_PREFIX, EeSsoConstants.CAS_PREFIX}) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * 浏览器页面导航（而非接口或静态资源）
     */
    private boolean isPage(HttpServletRequest request, String stripped) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if (stripped.startsWith(AuthConstant.DE_API_PREFIX + "/") || stripped.equals(AuthConstant.DE_API_PREFIX)) {
            return false;
        }
        String last = stripped.substring(stripped.lastIndexOf('/') + 1);
        return last.isEmpty() || last.endsWith(".html") || !last.contains(".");
    }

    private String currentUri(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return request.getQueryString() == null ? uri : uri + "?" + request.getQueryString();
    }

    /**
     * 只允许跳回本站网关前缀下的地址，防止开放重定向
     */
    private String safeReturn(String ret, String prefix) {
        String fallback = support.contextPath() + prefix + "/";
        if (StringUtils.isBlank(ret) || !ret.startsWith("/") || ret.startsWith("//") || ret.contains("\\")
                || !ret.startsWith(support.contextPath() + prefix)) {
            return fallback;
        }
        return ret;
    }

    private void error(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>SSO</title></head><body><h3>Single sign-on failed</h3><p>"
                + EeXml.escape(StringUtils.defaultIfBlank(message, "unknown error")) + "</p></body></html>");
    }

    /**
     * 去掉网关前缀后的请求视图
     */
    static class StrippedRequest extends HttpServletRequestWrapper {

        private final String contextPath;

        private final String path;

        StrippedRequest(HttpServletRequest request, String contextPath, String path) {
            super(request);
            this.contextPath = contextPath;
            this.path = path;
        }

        /**
         * 容器在本包装之下插入转发 / 包含请求（如 IndexController 转发到 index.html），此时以容器的路径为准
         */
        private boolean dispatched() {
            return getDispatcherType() != DispatcherType.REQUEST;
        }

        @Override
        public String getRequestURI() {
            return dispatched() ? super.getRequestURI() : contextPath + path;
        }

        @Override
        public StringBuffer getRequestURL() {
            if (dispatched()) {
                return super.getRequestURL();
            }
            HttpServletRequest request = (HttpServletRequest) getRequest();
            StringBuffer url = new StringBuffer();
            url.append(request.getScheme()).append("://").append(request.getServerName());
            int port = request.getServerPort();
            if (port > 0 && !((port == 80 && "http".equals(request.getScheme())) || (port == 443 && "https".equals(request.getScheme())))) {
                url.append(':').append(port);
            }
            return url.append(getRequestURI());
        }

        @Override
        public String getServletPath() {
            return dispatched() ? super.getServletPath() : path;
        }

        @Override
        public String getPathInfo() {
            return dispatched() ? super.getPathInfo() : null;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String target) {
            if (target != null && !target.startsWith("/") && !dispatched()) {
                String base = path.substring(0, path.lastIndexOf('/') + 1);
                target = base + target;
            }
            return super.getRequestDispatcher(target);
        }
    }
}
