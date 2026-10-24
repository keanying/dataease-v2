package io.dataease.ee.sso.support;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 调用第三方身份源 / 开放平台的 HTTP 客户端（JDK HttpClient）。
 *
 * <p>默认严格校验 TLS 证书；内网自签名证书可通过 {@code dataease.ee.sso.trust-all-certificates=true} 放开。
 * 代理遵循 JVM 系统属性（https.proxyHost 等）。</p>
 */
@Component
public class EeHttp {

    @Value("${dataease.ee.sso.http-timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${dataease.ee.sso.trust-all-certificates:false}")
    private boolean trustAll;

    private HttpClient client;

    @PostConstruct
    public void init() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 10))))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault());
        if (trustAll) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
                SSLParameters parameters = new SSLParameters();
                parameters.setEndpointIdentificationAlgorithm(null);
                builder.sslContext(context).sslParameters(parameters);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        client = builder.build();
    }

    public Response get(String url, Map<String, String> headers) {
        return send(base(url, headers).GET().build());
    }

    public Response delete(String url, Map<String, String> headers) {
        return send(base(url, headers).DELETE().build());
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        HttpRequest request = base(url, headers)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    public Response postJson(String url, Object body, Map<String, String> headers) {
        return sendJson("POST", url, body, headers);
    }

    public Response putJson(String url, Object body, Map<String, String> headers) {
        return sendJson("PUT", url, body, headers);
    }

    private Response sendJson(String method, String url, Object body, Map<String, String> headers) {
        String json = body == null ? "{}" : (body instanceof String s ? s : EeJson.write(body));
        HttpRequest request = base(url, headers)
                .header("Content-Type", "application/json;charset=UTF-8")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    /**
     * multipart/form-data 上传
     *
     * @param fields    普通字段
     * @param fileField 文件字段名
     */
    public Response postMultipart(String url, Map<String, String> fields, String fileField, String fileName, byte[] content,
                                  String contentType, Map<String, String> headers) {
        String boundary = "----DataEase" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (fields != null) {
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(StringUtils.defaultString(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            String safeName = StringUtils.defaultIfBlank(fileName, "file").replace("\"", "");
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + safeName
                    + "\"\r\nContent-Type: " + StringUtils.defaultIfBlank(contentType, "application/octet-stream") + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(content == null ? new byte[0] : content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest request = base(url, headers)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return send(request);
    }

    public static String form(Map<String, String> form) {
        if (form == null) {
            return "";
        }
        return form.entrySet().stream().filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    public static String encode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    /**
     * 在 url 后追加查询参数
     */
    public static String url(String base, Map<String, String> query) {
        String q = form(query);
        if (StringUtils.isBlank(q)) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + q;
    }

    public static Map<String, String> headers(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private HttpRequest.Builder base(String url, Map<String, String> headers) {
        URI uri;
        try {
            uri = URI.create(StringUtils.trim(url));
        } catch (Exception e) {
            throw new EeHttpException("invalid url: " + url);
        }
        if (!StringUtils.equalsAnyIgnoreCase(uri.getScheme(), "http", "https")) {
            throw new EeHttpException("invalid url: " + url);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        builder.header("Accept", "application/json, application/xml, text/xml, */*");
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    builder.header(k, v);
                }
            });
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EeHttpException("request interrupted: " + request.uri().getHost());
        } catch (IOException e) {
            String reason = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            throw new EeHttpException("request " + request.uri().getScheme() + "://" + request.uri().getAuthority() + " failed: " + reason);
        }
    }

    public record Response(int status, String body, Map<String, List<String>> headers) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonNode json() {
            return EeJson.tree(body);
        }

        /**
         * 非 2xx 时抛出异常
         */
        public Response require(String what) {
            if (!ok()) {
                throw new EeHttpException(what + " failed, HTTP " + status + ": " + StringUtils.abbreviate(StringUtils.defaultString(body), 300));
            }
            return this;
        }
    }

    /**
     * 第三方调用失败
     */
    public static class EeHttpException extends RuntimeException {
        public EeHttpException(String message) {
            super(message);
        }
    }

    private static class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
