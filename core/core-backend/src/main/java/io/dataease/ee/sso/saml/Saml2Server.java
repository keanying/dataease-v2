package io.dataease.ee.sso.saml;

import io.dataease.api.xpack.settings.XpackSaml2Api;
import io.dataease.ee.sso.support.EeXml;
import io.dataease.utils.LogUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SAML2 SP 端点：{@code /saml/login}、{@code /saml/sso}（ACS）、{@code /saml/metadata}。
 *
 * <p>这些地址相对 SPA 根路径（不在 /de2api 下）。CorsConfig 只为 {@code @RestController} 自动添加 /de2api 前缀，
 * 因此这里使用 {@code @Controller + @ResponseBody}。</p>
 */
@Controller("xpackSaml2Server")
@ResponseBody
@RequestMapping("/saml")
public class Saml2Server implements XpackSaml2Api {

    private final EeSaml2Manage saml2Manage;

    public Saml2Server(EeSaml2Manage saml2Manage) {
        this.saml2Manage = saml2Manage;
    }

    @Override
    public String sso() {
        HttpServletRequest request = ServletUtils.request();
        HttpServletResponse response = ServletUtils.response();
        try {
            String target = saml2Manage.acs(request == null ? null : request.getParameter("SAMLResponse"));
            response.sendRedirect(target);
        } catch (Exception e) {
            LogUtil.warn("saml2 acs failed: " + e.getMessage());
            error(response, e.getMessage());
        }
        return null;
    }

    @Override
    public String metadata() {
        return saml2Manage.metadata();
    }

    @Override
    public void login() {
        HttpServletResponse response = ServletUtils.response();
        try {
            EeSaml2Manage.LoginRequest login = saml2Manage.login();
            if (login.redirectUrl() != null) {
                response.sendRedirect(login.redirectUrl());
            } else {
                response.setContentType("text/html;charset=UTF-8");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(login.html());
                response.flushBuffer();
            }
        } catch (Exception e) {
            LogUtil.warn("saml2 login failed: " + e.getMessage());
            error(response, e.getMessage());
        }
    }

    private void error(HttpServletResponse response, String message) {
        if (response == null || response.isCommitted()) {
            return;
        }
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/html;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>SAML2</title></head><body>"
                    + "<h3>Single sign-on failed</h3><p>" + EeXml.escape(StringUtils.defaultIfBlank(message, "unknown error"))
                    + "</p><p><a href=\"../\">Back</a></p></body></html>");
            response.flushBuffer();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
