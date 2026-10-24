package io.dataease.ee.setting.security;

import io.dataease.constant.AuthConstant;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.result.ResultMessage;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import io.dataease.utils.WhitelistUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HMAC 请求签名校验（{@code hmac.enable=true} 时生效）。
 *
 * <p>前端对每个 axios 请求签名：{@code Authorization: Signature keyId="dataease-key",algorithm="hmac-sha256",
 * headers="@request-target X-Date",signature="..."}、{@code X-Date: <GMT 时间>}。服务端按请求 URI 重建签名路径
 * （含上下文路径与 /de2api、查询串），兼容 axios 查询串编码、绝对地址（div 嵌入）与 casbi/oidcbi 前缀。</p>
 *
 * <p>不校验：OPTIONS、非 /de2api 请求（静态资源、doc.html、websocket 等）、白名单接口（{@link WhitelistUtils}，
 * 含图片、字体、导出下载、hmac/info 等非 axios 请求）以及使用 API Key（{@code X-DE-ASK-TOKEN}）的服务端调用。</p>
 */
public class EeHmacFilter implements Filter {

    private static final Pattern SIGNATURE = Pattern.compile("signature=\"([^\"]*)\"");

    private static final Pattern KEY_ID = Pattern.compile("keyId=\"([^\"]*)\"");

    private static final Pattern ALGORITHM = Pattern.compile("algorithm=\"([^\"]*)\"");

    private final EeHmacManage hmacManage;

    public EeHmacFilter(EeHmacManage hmacManage) {
        this.hmacManage = hmacManage;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (exempt(request)) {
            chain.doFilter(request, response);
            return;
        }
        EeHmacManage.Config config;
        try {
            config = hmacManage.config();
        } catch (Exception e) {
            LogUtil.error("load hmac setting failed", e);
            chain.doFilter(request, response);
            return;
        }
        if (!config.enable()) {
            chain.doFilter(request, response);
            return;
        }
        String error = verify(request, config);
        if (error != null) {
            LogUtil.warn("hmac check failed [" + request.getMethod() + " " + request.getRequestURI() + "]: " + error);
            reject(response, Translator.get("ee_settings.hmac_signature_invalid"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean exempt(HttpServletRequest request) {
        if (StringUtils.equalsIgnoreCase(request.getMethod(), "OPTIONS")) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!StringUtils.contains(uri, AuthConstant.DE_API_PREFIX + "/")) {
            return true;
        }
        if (StringUtils.isNotBlank(request.getHeader(AuthConstant.ASK_TOKEN_KEY))) {
            return true;
        }
        try {
            return WhitelistUtils.match(uri);
        } catch (DEException e) {
            // 非法地址交由后续过滤器处理
            return true;
        }
    }

    /**
     * @return 失败原因，成功返回 null
     */
    String verify(HttpServletRequest request, EeHmacManage.Config config) {
        String authorization = request.getHeader("Authorization");
        String date = request.getHeader("X-Date");
        if (StringUtils.isAnyBlank(authorization, date) || !StringUtils.startsWithIgnoreCase(authorization.trim(), "Signature")) {
            return "signature missing";
        }
        String keyId = group(KEY_ID, authorization);
        String algorithm = group(ALGORITHM, authorization);
        String signature = group(SIGNATURE, authorization);
        if (!StringUtils.equals(keyId, EeHmacManage.KEY_ID) || !StringUtils.equalsIgnoreCase(algorithm, "hmac-sha256")
                || StringUtils.isBlank(signature)) {
            return "signature malformed";
        }
        long time;
        try {
            time = ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception e) {
            return "X-Date invalid";
        }
        if (Math.abs(System.currentTimeMillis() - time) > config.clockSkew() * 1000L) {
            return "X-Date expired";
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        for (String path : candidatePaths(request)) {
            String expected = EeHmacManage.sign(config.secret(), EeHmacManage.signingString(method, path, date));
            if (EeHmacManage.constantEquals(expected, signature)) {
                return null;
            }
        }
        return "signature mismatch";
    }

    private String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 前端可能签名的请求路径
     */
    Set<String> candidatePaths(HttpServletRequest request) {
        String uri = request.getRequestURI();
        Set<String> paths = new LinkedHashSet<>();
        paths.add(uri);
        paths.add(uri.replace("/casbi", "").replace("/oidcbi", ""));
        String suffix = StringUtils.substring(uri, uri.indexOf(AuthConstant.DE_API_PREFIX + "/"));
        paths.add(suffix);
        // 前端以 location.pathname 拼接相对 baseURL（./de2api），在 panel.html 等页面时形如 /panel.htmlde2api/...
        String referer = request.getHeader("Referer");
        if (StringUtils.isNotBlank(referer)) {
            try {
                String refererPath = URI.create(referer).getPath();
                if (StringUtils.isNotBlank(refererPath)) {
                    paths.add(refererPath.replace("mobile.html", "") + suffix.substring(1));
                }
            } catch (Exception ignored) {
                // 非法 Referer 忽略
            }
        }
        // div 嵌入时为绝对地址
        String host = StringUtils.defaultIfBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        List<String> base = new ArrayList<>(paths);
        if (StringUtils.isNotBlank(host)) {
            for (String path : base) {
                paths.add("http://" + host + path);
                paths.add("https://" + host + path);
            }
        }
        String query = request.getQueryString();
        if (StringUtils.isBlank(query)) {
            return paths;
        }
        Set<String> queries = new LinkedHashSet<>();
        queries.add(query);
        queries.add(axiosEncode(query));
        Set<String> result = new LinkedHashSet<>();
        for (String path : paths) {
            for (String q : queries) {
                result.add(path + "?" + q);
            }
        }
        return result;
    }

    /**
     * 按 axios buildURL 规则重新编码查询串：encodeURIComponent，但保留 : $ , [ ]，空格编码为 +
     */
    static String axiosEncode(String query) {
        StringJoiner joiner = new StringJoiner("&");
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? null : pair.substring(idx + 1);
            String encoded = encode(decode(key));
            if (value != null) {
                encoded += "=" + encode(decode(value));
            }
            joiner.add(encoded);
        }
        return joiner.toString();
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return text;
        }
    }

    private static String encode(String text) {
        // URLEncoder 等价于 encodeURIComponent，差异：空格为 +，且会编码 ! ' ( ) ~ *
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
        return encoded.replace("%3A", ":").replace("%24", "$").replace("%2C", ",").replace("%5B", "[").replace("%5D", "]");
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Object body = JsonUtil.toJSONString(new ResultMessage(HttpStatus.UNAUTHORIZED.value(), message));
        response.getWriter().write(body == null ? "" : body.toString());
    }
}
