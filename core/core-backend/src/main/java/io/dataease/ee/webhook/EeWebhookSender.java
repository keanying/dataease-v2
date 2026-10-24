package io.dataease.ee.webhook;

import io.dataease.ee.webhook.dao.XpackWebhook;
import io.dataease.ee.webhook.dao.XpackWebhookRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Webhook 发送器（可供定时报告、阈值告警等模块复用）。
 *
 * <h3>请求</h3>
 * <ul>
 *     <li>POST 到配置的 url；{@code ssl=false} 时信任全部证书且不校验主机名；</li>
 *     <li>{@code application/json}：以消息模板为请求体，模板为空时使用
 *     {@code {"title":"${title}","content":"${content}","messageId":"${messageId}"}}；占位符 {@code ${key}}
 *     替换为 payload 中同名值的 JSON 转义文本（不含引号，因此模板中的占位符应写在字符串引号内）；
 *     未提供的占位符替换为空串；</li>
 *     <li>{@code application/x-www-form-urlencoded}：payload 的每个键值作为表单字段。</li>
 * </ul>
 *
 * <h3>签名（仅当配置了 secret）</h3>
 * <ul>
 *     <li>{@code timestamp} = 当前毫秒时间戳；</li>
 *     <li>{@code sign} = Base64( HmacSHA256( key = secret, data = timestamp + "\n" + secret ) )
 *     —— 与钉钉自定义机器人加签算法一致，与请求体无关；</li>
 *     <li>{@code bodySign} = Base64( HmacSHA256( key = secret, data = timestamp + "\n" + 请求体 ) )
 *     —— 接收方可用于校验请求体完整性；</li>
 *     <li>请求头：{@code X-DE-Timestamp}、{@code X-DE-Signature}（sign）、{@code X-DE-Body-Signature}（bodySign）；</li>
 *     <li>url 与模板中可以使用 {@code ${timestamp}}、{@code ${sign}} 占位符（url 中会做 URL 编码），
 *     例如钉钉：{@code https://oapi.dingtalk.com/robot/send?access_token=xxx&timestamp=${timestamp}&sign=${sign}}；
 *     表单请求还会附带 timestamp、sign 两个字段。</li>
 * </ul>
 */
@Component
public class EeWebhookSender {

    public static final String JSON = "application/json";

    public static final String FORM = "application/x-www-form-urlencoded";

    public static final String DEFAULT_TEMPLATE = "{\"title\":\"${title}\",\"content\":\"${content}\",\"messageId\":\"${messageId}\"}";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

    private static final int CONNECT_TIMEOUT = 10000;

    private static final int READ_TIMEOUT = 30000;

    private final XpackWebhookRepository repository;

    public EeWebhookSender(XpackWebhookRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 ID 发送（不校验组织，调用方负责确认 webhook 属于业务所在组织）
     *
     * @param payload 占位符取值，常用 title、content、messageId
     */
    public Result send(Long webhookId, Map<String, ?> payload) {
        XpackWebhook webhook = webhookId == null ? null : repository.findById(webhookId).orElse(null);
        if (webhook == null) {
            DEException.throwException(Translator.get("ee_webhook_not_exist"));
        }
        return send(webhook, payload);
    }

    /**
     * 按配置发送；网络错误或非 2xx 响应返回 success=false，不抛异常
     */
    public Result send(XpackWebhook webhook, Map<String, ?> payload) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((k, v) -> vars.put(k, v == null ? "" : v.toString()));
        }
        String secret = webhook.getSecret();
        Map<String, String> headers = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(secret)) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String sign = hmac(secret, timestamp + "\n" + secret);
            vars.put("timestamp", timestamp);
            vars.put("sign", sign);
            headers.put("X-DE-Timestamp", timestamp);
            headers.put("X-DE-Signature", sign);
        }
        String contentType = StringUtils.defaultIfBlank(webhook.getContentType(), JSON);
        String body;
        if (StringUtils.startsWithIgnoreCase(contentType, FORM)) {
            StringBuilder builder = new StringBuilder();
            vars.forEach((k, v) -> {
                if (!builder.isEmpty()) {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            body = builder.toString();
            contentType = FORM + ";charset=UTF-8";
        } else {
            String template = StringUtils.defaultIfBlank(webhook.getMsgTemplate(), DEFAULT_TEMPLATE);
            body = substitute(template, vars, true);
            contentType = JSON + ";charset=UTF-8";
        }
        if (StringUtils.isNotBlank(secret)) {
            headers.put("X-DE-Body-Signature", hmac(secret, vars.get("timestamp") + "\n" + body));
        }
        String url = substitute(webhook.getUrl(), vars, false);
        return post(url, contentType, body, headers, !Boolean.FALSE.equals(webhook.getSsl()));
    }

    private Result post(String url, String contentType, String body, Map<String, String> headers, boolean verifySsl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            if (!verifySsl && connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustAll());
                https.setHostnameVerifier((host, session) -> true);
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("User-Agent", "DataEase-Webhook");
            headers.forEach(connection::setRequestProperty);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            boolean success = status >= 200 && status < 300;
            return new Result(success, status, response, success ? null : "HTTP " + status);
        } catch (Exception e) {
            LogUtil.warn("webhook send failed: " + url + " " + e.getMessage());
            return new Result(false, 0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int n;
            while ((n = input.read(buffer)) > 0 && total < 65536) {
                out.write(buffer, 0, n);
                total += n;
            }
            return StringUtils.abbreviate(out.toString(StandardCharsets.UTF_8), 2000);
        }
    }

    /**
     * 替换 ${key}；json=true 时按 JSON 字符串内容转义，否则 URL 编码
     */
    public static String substitute(String template, Map<String, String> vars, boolean json) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value;
            if (json) {
                value = escapeJson(vars.getOrDefault(key, ""));
            } else {
                if (!vars.containsKey(key)) {
                    // url 中未知占位符保持原样
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                value = URLEncoder.encode(vars.get(key), StandardCharsets.UTF_8);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SSLSocketFactory trustAll() throws Exception {
        TrustManager[] managers = {new X509TrustManager() {
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
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers, new SecureRandom());
        return context.getSocketFactory();
    }

    /**
     * 发送结果
     *
     * @param success 是否 2xx
     * @param status  HTTP 状态码，网络错误时为 0
     * @param body    响应体（截断到 2000 字符）
     * @param error   失败原因
     */
    public record Result(boolean success, int status, String body, String error) {
    }
}
