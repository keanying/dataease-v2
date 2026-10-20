package io.dataease.ee.auth;

import io.dataease.ee.common.EeConstants;
import io.dataease.exception.DEException;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultMessage;
import io.dataease.utils.CommunityUtils;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LocalModelUtils;
import io.dataease.utils.LogUtil;
import io.dataease.utils.WhitelistUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统一身份识别过滤器：白名单放行，其余请求必须携带有效凭证。
 */
public class EeTokenFilter implements Filter {

    private final List<EeRequestAuthenticator> authenticators;

    public EeTokenFilter(List<EeRequestAuthenticator> authenticators) {
        this.authenticators = authenticators;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String method = request.getMethod();
        if (!StringUtils.equalsAnyIgnoreCase(method, "GET", "POST", "OPTIONS", "DELETE")) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (StringUtils.equalsIgnoreCase("OPTIONS", method)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            boolean whitelisted;
            try {
                whitelisted = WhitelistUtils.match(request.getRequestURI());
            } catch (DEException e) {
                unauthorized(response, e.getMessage());
                return;
            }
            if (LocalModelUtils.isDesktop()) {
                V3UserUtil.setContext(EeConstants.ADMIN_UID, EeConstants.ROOT_ORG_ID);
                chain.doFilter(request, response);
                return;
            }
            EeRequestAuthenticator authenticator = authenticators.stream().filter(a -> a.supports(request)).findFirst().orElse(null);
            if (authenticator == null) {
                if (whitelisted) {
                    chain.doFilter(request, response);
                } else {
                    unauthorized(response, "token is empty for uri {" + request.getRequestURI() + "}");
                }
                return;
            }
            try {
                authenticator.authenticate(request);
            } catch (Exception e) {
                V3UserUtil.clear();
                if (whitelisted) {
                    chain.doFilter(request, response);
                } else {
                    LogUtil.warn("authentication failed: " + e.getMessage());
                    unauthorized(response, e.getMessage());
                }
                return;
            }
            chain.doFilter(request, response);
        } finally {
            V3UserUtil.clear();
            CommunityUtils.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        String msg = StringUtils.defaultIfBlank(message, "unauthorized");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.addHeader(EeConstants.GATEWAY_FLAG_HEADER, URLEncoder.encode(msg, StandardCharsets.UTF_8).replace("+", "%20"));
        Object body = JsonUtil.toJSONString(new ResultMessage(HttpStatus.UNAUTHORIZED.value(), msg));
        response.getWriter().write(body == null ? "" : body.toString());
    }
}
