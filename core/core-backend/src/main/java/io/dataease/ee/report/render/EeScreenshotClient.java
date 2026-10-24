package io.dataease.ee.report.render;

import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.HttpClientConfig;
import io.dataease.utils.HttpClientUtil;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * de-playwright-api 截图服务客户端（官方部署见 installer/dataease/docker-compose-playwright.yml）。
 *
 * <p>配置：</p>
 * <ul>
 *     <li>{@code dataease.playwright-server}：截图接口地址，如 {@code http://de-playwright-api:3000/screenshot}；为空表示未部署截图服务；</li>
 *     <li>{@code dataease.report.base-url}：截图服务访问 DataEase 前端的地址，默认
 *     {@code http://dataease:${server.port}${server.servlet.context-path}}（docker 网络内的服务名）。</li>
 * </ul>
 *
 * <p>请求：POST JSON（字段均为字符串）{@code url, token, type(jpeg|pdf), width, height, waitTime, renderTime,
 * timeout, localStorage}；其中 localStorage 为需要预置到页面 localStorage 的键值（JSON 字符串），
 * 包含前端 web-storage-cache 格式的 {@code <prefix>user.token / user.uid / user.oid}。
 * 响应：multipart，{@code name="image"} 部分为文件内容（jpeg 或 pdf），其余部分为 JSON 元数据。</p>
 */
@Component
public class EeScreenshotClient {

    @Value("${dataease.playwright-server:}")
    private String server;

    @Value("${dataease.report.base-url:}")
    private String baseUrl;

    @Value("${server.port:8100}")
    private String serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    public boolean configured() {
        return StringUtils.isNotBlank(server) && !StringUtils.startsWith(server.trim(), "${");
    }

    public String frontendBase() {
        String base = StringUtils.isNotBlank(baseUrl) ? baseUrl.trim()
                : "http://dataease:" + serverPort + StringUtils.defaultString(contextPath);
        return StringUtils.removeEnd(base, "/") + "/";
    }

    /**
     * 仪表板/大屏预览地址（前端 PreviewCanvas 读取 taskId 后以 source=report 加载定时报告过滤条件）
     */
    public String previewUrl(Long dvId, String dvType, Long taskId, boolean showWatermark) {
        return frontendBase() + "#/preview?dvId=" + dvId + "&dvType=" + URLEncoder.encode(dvType, StandardCharsets.UTF_8)
                + "&taskId=" + taskId + "&showWatermark=" + showWatermark + "&ignoreParams=true";
    }

    /**
     * 截图
     *
     * @return 文件内容
     */
    public byte[] capture(ScreenshotRequest request) {
        if (!configured()) {
            DEException.throwException(Translator.get("ee_report_screenshot_not_config"));
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", request.url());
        body.put("token", request.token());
        body.put("type", request.pdf() ? "pdf" : "jpeg");
        body.put("width", String.valueOf(request.width()));
        body.put("height", String.valueOf(request.height()));
        body.put("waitTime", String.valueOf(request.waitTime()));
        body.put("renderTime", String.valueOf(request.renderTime()));
        int timeoutSeconds = Math.max(60, request.waitTime() + request.renderTime() + 60);
        body.put("timeout", String.valueOf(timeoutSeconds));
        body.put("localStorage", JsonUtil.toJSONString(localStorage(request)).toString());
        HttpClientConfig config = new HttpClientConfig();
        config.setConnectTimeout(10000);
        config.setConnectionRequestTimeout(10000);
        config.setSocketTimeout(timeoutSeconds * 1000);
        try {
            HttpClientUtil.MultipartResponse response = HttpClientUtil.postForScreenshot(server.trim(), body, config);
            if (response == null || response.getImage() == null || response.getImage().length == 0) {
                Object error = response == null || response.getMetadata() == null ? null : response.getMetadata().get("error");
                DEException.throwException(Translator.get("ee_report_screenshot_failed", error == null ? "empty response" : error.toString()));
            }
            return response.getImage();
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            DEException.throwException(Translator.get("ee_report_screenshot_failed",
                    StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName())));
            return null;
        }
    }

    /**
     * 前端 useCache 的 key 前缀：路径首段 + "_"，根路径为 de_v3_；值为 web-storage-cache 格式
     */
    private Map<String, String> localStorage(ScreenshotRequest request) {
        String path = StringUtils.defaultString(contextPath).replaceAll("^/+", "");
        String first = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String prefix = StringUtils.isBlank(first) ? "de_v3_" : first + "_";
        long now = System.currentTimeMillis();
        Map<String, String> map = new HashMap<>();
        map.put(prefix + "user.token", cacheValue(request.token(), now));
        map.put(prefix + "user.uid", cacheValue(String.valueOf(request.uid()), now));
        if (request.oid() != null) {
            map.put(prefix + "user.oid", cacheValue(String.valueOf(request.oid()), now));
        }
        return map;
    }

    private String cacheValue(String value, long now) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("c", now);
        item.put("e", 8640000000000000L);
        item.put("v", JsonUtil.toJSONString(value).toString());
        return JsonUtil.toJSONString(item).toString();
    }

    public record ScreenshotRequest(String url, String token, Long uid, Long oid, boolean pdf,
                                    int width, int height, int waitTime, int renderTime) {
    }
}
