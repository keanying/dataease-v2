package io.dataease.ee.sync.provider;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

/**
 * 同步模块使用的轻量 HTTP 客户端（基于核心已依赖的 Apache HttpClient 4）：
 * Basic 认证、JSON 请求体、不自动跳转（由调用方处理 307）、可中止。
 *
 * <p>与核心 {@code HttpClientUtil} 一致：https 地址信任自签名证书（Elasticsearch / Doris 常见部署）。</p>
 */
public final class SyncHttp implements Closeable {

    /**
     * 响应
     */
    public record Response(int status, String body, String location) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean redirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }

    /**
     * 任意方法（含带请求体的 DELETE / GET）
     */
    public static class Request extends HttpEntityEnclosingRequestBase {
        private final String method;

        public Request(String method, String uri) {
            this.method = method;
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return method;
        }
    }

    private final CloseableHttpClient client;
    private final String authorization;
    private final RequestConfig requestConfig;

    public SyncHttp(String username, String password, int connectTimeoutMs, int socketTimeoutMs) {
        this.client = build();
        this.authorization = StringUtils.isEmpty(username) ? null
                : "Basic " + Base64.getEncoder().encodeToString((username + ":" + StringUtils.defaultString(password)).getBytes(StandardCharsets.UTF_8));
        this.requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .setRedirectsEnabled(false)
                .build();
    }

    private static CloseableHttpClient build() {
        try {
            SSLContextBuilder builder = new SSLContextBuilder();
            builder.loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true);
            SSLConnectionSocketFactory ssl = new SSLConnectionSocketFactory(builder.build(), NoopHostnameVerifier.INSTANCE);
            Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", ssl).build();
            PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(registry);
            manager.setMaxTotal(8);
            manager.setDefaultMaxPerRoute(4);
            return HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                    .disableAutomaticRetries().build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Response json(String method, String url, String body, SyncCancellation cancellation) throws IOException {
        Request request = new Request(method, url);
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON));
        }
        return execute(request, null, false, cancellation);
    }

    /**
     * 执行请求；headers 为附加请求头，expectContinue 为是否启用 {@code Expect: 100-continue}
     */
    public Response execute(HttpRequestBase request, Map<String, String> headers, boolean expectContinue,
                            SyncCancellation cancellation) throws IOException {
        RequestConfig config = expectContinue ? RequestConfig.copy(requestConfig).setExpectContinueEnabled(true).build() : requestConfig;
        request.setConfig(config);
        if (authorization != null) {
            request.setHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (headers != null) {
            headers.forEach(request::setHeader);
        }
        SyncCancellation control = cancellation == null ? SyncCancellation.NONE : cancellation;
        control.bindAbort(request::abort);
        try (CloseableHttpResponse response = client.execute(request)) {
            HttpEntity entity = response.getEntity();
            String text = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            return new Response(response.getStatusLine().getStatusCode(), text, location == null ? null : location.getValue());
        } finally {
            control.bindAbort(null);
        }
    }

    /**
     * 去掉结尾的 /
     */
    public static String trimBase(String url) {
        String value = StringUtils.trimToEmpty(url);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
