package io.dataease.ee.sync.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Doris Stream Load 客户端（HTTP PUT {@code /api/{db}/{table}/_stream_load}）。
 *
 * <p>请求发往 FE HTTP 端口，FE 以 307 重定向到某个 BE；Basic 认证、{@code Expect: 100-continue}、
 * {@code format: json} + {@code strip_outer_array: true}，每批一个 label（同 label 重试幂等）。
 * 若配置了 BE 地址 / 端口，重定向地址中的主机 / 端口替换为配置值（BE 内网地址不可达的部署）。</p>
 */
public class DorisStreamLoader implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SUCCESS = Set.of("success", "publish timeout");

    private static final int MAX_REDIRECTS = 3;

    /**
     * 连接 FE / BE 失败（可回退 JDBC）
     */
    public static class UnavailableException extends IOException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 导入失败（数据或服务端错误，不回退）
     */
    public static class LoadException extends IOException {
        private final String errorUrl;

        public LoadException(String message, String errorUrl) {
            super(message);
            this.errorUrl = errorUrl;
        }

        public String getErrorUrl() {
            return errorUrl;
        }
    }

    /**
     * 导入结果
     */
    public record Result(String label, String status, long totalRows, long loadedRows, long filteredRows,
                         long unselectedRows, String message, String errorUrl, String beHost) {
    }

    private final String feBase;
    private final String beHost;
    private final Integer bePort;
    private final String database;
    private final SyncHttp http;

    /**
     * @param feBase   FE 地址，如 http://fe:8030
     * @param beHost   覆盖重定向的 BE 主机（可空）
     * @param bePort   覆盖重定向的 BE 端口（可空）
     * @param database 数据库
     */
    public DorisStreamLoader(String feBase, String beHost, Integer bePort, String database, String username, String password,
                             int socketTimeoutMs) {
        this.feBase = SyncHttp.trimBase(feBase);
        this.beHost = StringUtils.trimToNull(beHost);
        this.bePort = bePort != null && bePort > 0 ? bePort : null;
        this.database = database;
        this.http = new SyncHttp(username, password, 10_000, socketTimeoutMs);
    }

    public String getFeBase() {
        return feBase;
    }

    public String url(String table) {
        return feBase + "/api/" + encode(database) + "/" + encode(table) + "/_stream_load";
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 导入一批 JSON 数组数据
     *
     * @param maxFilterRatio 允许过滤（数据质量不合格）的行比例，0 表示任何错误行都导致失败
     */
    public Result load(String table, List<String> columns, byte[] jsonArray, String label, double maxFilterRatio,
                       SyncCancellation cancellation) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("label", label);
        headers.put("format", "json");
        headers.put("strip_outer_array", "true");
        // 数值以文本解析，避免 DECIMAL 精度损失
        headers.put("num_as_string", "true");
        headers.put("columns", columns.stream().map(c -> "`" + c.replace("`", "``") + "`").collect(Collectors.joining(",")));
        if (maxFilterRatio > 0) {
            headers.put("max_filter_ratio", String.valueOf(Math.min(1.0, maxFilterRatio)));
        }
        String url = url(table);
        boolean redirected = false;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpPut put = new HttpPut(url);
            put.setEntity(new ByteArrayEntity(jsonArray, ContentType.APPLICATION_JSON));
            SyncHttp.Response response;
            try {
                response = http.execute(put, headers, true, cancellation);
            } catch (IOException e) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw e;
                }
                // 首次连接 FE 失败视为不可用；重定向后连接 BE 失败同样提示（通常为 BE 地址不可达）
                throw new UnavailableException((redirected ? "BE " : "FE ") + URI.create(url).getAuthority() + ": "
                        + SyncJdbcSupport.rootMessage(e), e);
            }
            if (response.redirect()) {
                if (StringUtils.isBlank(response.location())) {
                    throw new LoadException("redirect without Location (HTTP " + response.status() + ")", null);
                }
                url = rewrite(URI.create(url).resolve(response.location()).toString());
                redirected = true;
                continue;
            }
            if (response.status() == 401 || response.status() == 403) {
                throw new LoadException("HTTP " + response.status() + " " + StringUtils.left(response.body(), 300), null);
            }
            if (response.status() == 404 || response.status() == 405) {
                // 端口不是 Doris HTTP 服务
                throw new UnavailableException("HTTP " + response.status() + " " + url, null);
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(StringUtils.defaultIfBlank(response.body(), "{}"));
            } catch (Exception e) {
                throw new LoadException("HTTP " + response.status() + " " + StringUtils.left(response.body(), 300), null);
            }
            return result(label, node, URI.create(url).getAuthority(), response.status());
        }
        throw new LoadException("too many redirects: " + url, null);
    }

    private Result result(String label, JsonNode node, String host, int httpStatus) throws LoadException {
        String status = node.path("Status").asText("");
        Result result = new Result(node.path("Label").asText(label), status, node.path("NumberTotalRows").asLong(0),
                node.path("NumberLoadedRows").asLong(0), node.path("NumberFilteredRows").asLong(0),
                node.path("NumberUnselectedRows").asLong(0), node.path("Message").asText(node.path("msg").asText("")),
                StringUtils.trimToNull(node.path("ErrorURL").asText("")), host);
        String lower = status.toLowerCase(Locale.ROOT);
        if (SUCCESS.contains(lower)) {
            return result;
        }
        if ("label already exists".equals(lower)) {
            // 同一批重试：之前的导入已完成则视为成功
            String existing = node.path("ExistingJobStatus").asText("");
            if ("FINISHED".equalsIgnoreCase(existing) || "VISIBLE".equalsIgnoreCase(existing)) {
                return result;
            }
            throw new LoadException("label " + label + " already exists, job status " + existing, null);
        }
        String message = StringUtils.defaultIfBlank(result.message(), "HTTP " + httpStatus + " " + StringUtils.left(node.toString(), 300));
        throw new LoadException(StringUtils.isBlank(status) ? message : status + ": " + message, result.errorUrl());
    }

    /**
     * 按配置替换重定向目标的 BE 主机 / 端口
     */
    String rewrite(String location) {
        if (beHost == null && bePort == null) {
            return location;
        }
        URI uri = URI.create(location);
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), beHost == null ? uri.getHost() : beHost,
                    bePort == null ? uri.getPort() : bePort, uri.getRawPath() == null ? null : uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            return location;
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
