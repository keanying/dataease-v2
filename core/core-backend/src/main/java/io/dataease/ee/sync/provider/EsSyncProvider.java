package io.dataease.ee.sync.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.datatype.StandardDataType;
import io.dataease.extensions.sync.model.TableDTO;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.ConnectionObj;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.model.task.TaskInfoVO;
import io.dataease.extensions.sync.provider.SourceProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 源端（REST API，不依赖 ES 客户端）。
 *
 * <ul>
 *     <li>表：索引与别名（排除 . 开头的系统索引）；字段：{@code _mapping} 展开 object 子属性（路径用 . 连接），另加文档 {@code _id}；</li>
 *     <li>读取：scroll 分页（按 {@code _doc} 排序），{@code _source} 只取映射字段；</li>
 *     <li>查询参数：任务中的 {@code esQuery}（query 子句，或含 query 的完整请求体）；增量：追加 {@code range gt} 过滤。</li>
 * </ul>
 */
public class EsSyncProvider extends SyncProvider implements SourceProvider, SyncDialect, SyncRecordSource {

    public static final String TYPE = "elasticsearch";

    /**
     * 文档 ID 伪字段
     */
    public static final String ID_FIELD = "_id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int PAGE_SIZE = 2000;
    private static final String SCROLL_KEEP_ALIVE = "5m";
    private static final int KEYWORD_DEFAULT_SIZE = 512;

    private static final Set<String> STRING_TYPES = Set.of("keyword", "constant_keyword", "wildcard", "version", "text",
            "match_only_text", "search_as_you_type", "ip", "binary", "completion", "annotated_text");

    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd[['T'][' ']HH:mm[:ss]]")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .optionalStart().appendOffsetId().optionalEnd()
            .toFormatter(Locale.ROOT);

    /**
     * ES 字段：路径、ES 类型、标准类型、长度
     */
    public record EsField(String path, String esType, StandardDataType standard, int size) {
    }

    @Override
    public String getProviderName() {
        return TYPE;
    }

    // ------------------------------------------------------------------ 连接

    @Override
    public DatasourceConfiguration configuration(String configurationJson) {
        if (StringUtils.isBlank(configurationJson)) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        DatasourceConfiguration cfg = JsonUtil.parseObject(configurationJson, DatasourceConfiguration.class);
        String url = SyncHttp.trimBase(cfg == null ? null : cfg.getUrl());
        if (StringUtils.isBlank(url)) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            DEException.throwException(Translator.get("ee_sync_es_url_invalid"));
        }
        cfg.setUrl(url);
        return cfg;
    }

    private SyncHttp http(DatasourceConfiguration cfg) {
        int timeout = Math.max(cfg.getQueryTimeout(), 30) * 1000;
        return new SyncHttp(cfg.getUsername(), cfg.getPassword(), 10_000, Math.max(timeout, 60_000));
    }

    /**
     * 发送请求并解析 JSON；非 2xx 时抛出 ES 返回的错误原因
     */
    private JsonNode call(SyncHttp http, String method, String url, String body, SyncCancellation cancellation) throws IOException {
        SyncHttp.Response response = http.json(method, url, body, cancellation);
        JsonNode node = parse(response.body());
        if (!response.ok()) {
            throw new IOException(errorReason(response.status(), node, response.body()));
        }
        return node;
    }

    private static JsonNode parse(String text) {
        if (StringUtils.isBlank(text)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return MAPPER.createObjectNode().put("raw", StringUtils.left(text, 500));
        }
    }

    static String errorReason(int status, JsonNode node, String body) {
        JsonNode error = node.get("error");
        if (error != null) {
            if (error.isTextual()) {
                return "HTTP " + status + ": " + error.asText();
            }
            JsonNode root = error.path("root_cause");
            String type = error.path("type").asText("");
            String reason = error.path("reason").asText("");
            if (root.isArray() && !root.isEmpty() && StringUtils.isBlank(reason)) {
                reason = root.get(0).path("reason").asText("");
            }
            return "HTTP " + status + ": " + StringUtils.trimToEmpty(type + " " + reason);
        }
        return "HTTP " + status + (StringUtils.isBlank(body) ? "" : ": " + StringUtils.left(body, 300));
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private <T> T rethrow(Exception e) {
        if (e instanceof DEException de) {
            throw de;
        }
        DEException.throwException(Translator.get("ee_sync_es_error", SyncJdbcSupport.rootMessage(e)));
        return null;
    }

    @Override
    public List<String> getSchema(DatasourceRequest datasourceRequest) {
        return new ArrayList<>();
    }

    @Override
    public ConnectionObj getConnection(DatasourceConfiguration configuration) {
        DEException.throwException(Translator.get("ee_sync_es_jdbc_unsupported"));
        return null;
    }

    @Override
    public String checkStatus(DatasourceRequest request) {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        try (SyncHttp http = http(cfg)) {
            JsonNode root = call(http, "GET", cfg.getUrl() + "/", null, null);
            String version = root.path("version").path("number").asText("");
            if (StringUtils.isNotBlank(version)) {
                int major = Integer.parseInt(StringUtils.substringBefore(version, "."));
                if (major < 6) {
                    DEException.throwException(Translator.get("ee_sync_es_version_unsupported", version));
                }
            }
        } catch (Exception e) {
            if (e instanceof DEException de) {
                throw de;
            }
            DEException.throwException(Translator.get("ee_sync_connect_failed", SyncJdbcSupport.rootMessage(e)));
        }
        return "Success";
    }

    @Override
    public String validate(DatasourceRequest request) {
        checkConfiguration(request);
        return checkStatus(request);
    }

    @Override
    public void checkConfiguration(DatasourceRequest request) {
        configuration(request.getConfiguration());
    }

    // ------------------------------------------------------------------ 元数据

    @Override
    public List<TableDTO> getTables(DatasourceRequest request) {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        Map<String, TableDTO> tables = new LinkedHashMap<>();
        try (SyncHttp http = http(cfg)) {
            JsonNode indices = call(http, "GET", cfg.getUrl() + "/_cat/indices?format=json&h=index,docs.count,status&s=index", null, null);
            for (JsonNode node : indices) {
                String name = node.path("index").asText("");
                if (StringUtils.isBlank(name) || name.startsWith(".")) {
                    continue;
                }
                TableDTO dto = new TableDTO();
                dto.setDatasourceId(request.getId());
                dto.setName(name);
                String count = node.path("docs.count").asText("");
                dto.setRemark(StringUtils.isBlank(count) ? null : Translator.get("ee_sync_es_docs", count));
                tables.put(name, dto);
            }
            JsonNode aliases = call(http, "GET", cfg.getUrl() + "/_cat/aliases?format=json&h=alias,index&s=alias", null, null);
            for (JsonNode node : aliases) {
                String alias = node.path("alias").asText("");
                if (StringUtils.isBlank(alias) || alias.startsWith(".") || tables.containsKey(alias)) {
                    continue;
                }
                TableDTO dto = new TableDTO();
                dto.setDatasourceId(request.getId());
                dto.setName(alias);
                dto.setRemark(Translator.get("ee_sync_es_alias", node.path("index").asText("")));
                tables.put(alias, dto);
            }
        } catch (Exception e) {
            return rethrow(e);
        }
        return new ArrayList<>(tables.values());
    }

    @Override
    public List<TableFieldDTO> fetchTableField(DatasourceRequest request) {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        if (StringUtils.isBlank(request.getTable())) {
            DEException.throwException(Translator.get("ee_sync_task_source_table_empty"));
        }
        List<EsField> esFields;
        try (SyncHttp http = http(cfg)) {
            esFields = mapping(http, cfg, request.getTable(), null);
        } catch (Exception e) {
            return rethrow(e);
        }
        List<TableFieldDTO> fields = new ArrayList<>();
        TableFieldDTO id = new TableFieldDTO();
        id.setFieldSource(ID_FIELD);
        id.setFieldName(ID_FIELD);
        id.setFieldSourceType("_id");
        id.setFieldSourceStandardType(StandardDataType.KEYWORD.name());
        id.setFieldType(StandardDataType.KEYWORD.name());
        id.setFieldSize(KEYWORD_DEFAULT_SIZE);
        id.setFieldPk(true);
        id.setRemarks(Translator.get("ee_sync_es_id_remark"));
        fields.add(id);
        for (EsField f : esFields) {
            TableFieldDTO field = new TableFieldDTO();
            field.setFieldSource(f.path());
            // 目标列名不含 .（各数据库对含 . 的列名支持不一）
            field.setFieldName(f.path().replace('.', '_'));
            field.setFieldSourceType(f.esType());
            field.setFieldSourceStandardType(f.standard().name());
            field.setFieldType(f.standard().name());
            field.setFieldSize(f.size());
            field.setFieldPrecision(0);
            fields.add(field);
        }
        if (esFields.isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_table_no_field", request.getTable()));
        }
        return fields;
    }

    /**
     * 读取索引映射并展开为字段列表（多个索引时合并，先出现者优先）
     */
    List<EsField> mapping(SyncHttp http, DatasourceConfiguration cfg, String index, SyncCancellation cancellation) throws IOException {
        JsonNode root = call(http, "GET", cfg.getUrl() + "/" + encode(index) + "/_mapping", null, cancellation);
        Map<String, EsField> fields = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            JsonNode mappings = it.next().getValue().path("mappings");
            JsonNode properties = mappings.path("properties");
            if (properties.isMissingNode()) {
                // ES 6.x：mappings 下还有一层类型名
                Iterator<JsonNode> types = mappings.elements();
                while (types.hasNext() && properties.isMissingNode()) {
                    properties = types.next().path("properties");
                }
            }
            flatten("", properties, fields);
        }
        return new ArrayList<>(fields.values());
    }

    private static void flatten(String prefix, JsonNode properties, Map<String, EsField> out) {
        if (properties == null || !properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = prefix + entry.getKey();
            JsonNode def = entry.getValue();
            String type = def.path("type").asText("");
            if (StringUtils.isBlank(type) || "object".equals(type)) {
                if (def.has("properties")) {
                    flatten(path + ".", def.get("properties"), out);
                } else {
                    out.putIfAbsent(path, new EsField(path, "object", StandardDataType.OBJECT, 0));
                }
                continue;
            }
            if ("alias".equals(type)) {
                // 别名字段不在 _source 中
                continue;
            }
            int ignoreAbove = def.path("ignore_above").asInt(0);
            out.putIfAbsent(path, new EsField(path, type, standard(type), sizeOf(type, ignoreAbove)));
        }
    }

    /**
     * ES 字段类型 → 标准类型
     */
    public static StandardDataType standard(String esType) {
        return switch (StringUtils.defaultString(esType).toLowerCase(Locale.ROOT)) {
            case "long" -> StandardDataType.BIGINT;
            case "integer" -> StandardDataType.INTEGER;
            case "short" -> StandardDataType.SMALLINT;
            case "byte" -> StandardDataType.TINYINT;
            case "unsigned_long" -> StandardDataType.UNSIGNED_LONG;
            case "double" -> StandardDataType.DOUBLE;
            case "float" -> StandardDataType.FLOAT;
            case "half_float" -> StandardDataType.HALF_FLOAT;
            case "scaled_float" -> StandardDataType.SCALED_FLOAT;
            case "boolean" -> StandardDataType.BOOLEAN;
            case "keyword", "constant_keyword", "wildcard", "version" -> StandardDataType.KEYWORD;
            case "ip" -> StandardDataType.IP;
            case "date", "date_nanos" -> StandardDataType.TIMESTAMP;
            case "nested" -> StandardDataType.NESTED;
            case "flattened", "object" -> StandardDataType.OBJECT;
            // text / geo / range / vector / binary 等按文本保存（复杂值为 JSON 文本）
            default -> StandardDataType.TEXT;
        };
    }

    private static int sizeOf(String esType, int ignoreAbove) {
        return switch (esType) {
            case "keyword", "constant_keyword", "wildcard" -> ignoreAbove > 0 ? Math.max(ignoreAbove, 64) : KEYWORD_DEFAULT_SIZE;
            case "version" -> 128;
            case "ip" -> 45;
            default -> 0;
        };
    }

    @Override
    public List<TableFieldDTO> getFieldsBySql(DatasourceRequest request) {
        DEException.throwException(Translator.get("ee_sync_es_sql_unsupported"));
        return null;
    }

    @Override
    public String generatorSourceConfig(TaskInfoVO taskInfoVO) {
        Map<String, Object> es = new LinkedHashMap<>();
        es.put("plugin_name", "Elasticsearch");
        es.put("result_table_name", "fake");
        if (taskInfoVO.getSource() != null) {
            es.put("index", taskInfoVO.getSource().getTables());
            es.put("query", taskInfoVO.getSource().getEsQuery());
        }
        return JsonUtil.toJSONString(Map.of("Elasticsearch", es)).toString();
    }

    @Override
    public String appendIncrementConditions(TaskInfoVO taskInfo, String jobParameter) {
        return jobParameter;
    }

    @Override
    public StandardDataType dataTypeToStandardDataType(String sourceType) {
        return standard(sourceType);
    }

    // ------------------------------------------------------------------ 读取

    @Override
    public void validateSource(Source source) {
        if (io.dataease.ee.sync.SyncConstants.EXTRACT_SQL.equals(source.getTableExtract())) {
            DEException.throwException(Translator.get("ee_sync_es_sql_unsupported"));
        }
        userQuery(source.getEsQuery());
    }

    /**
     * 解析查询参数：query 子句，或含 query 的完整请求体；为空时 match_all
     */
    static JsonNode userQuery(String esQuery) {
        if (StringUtils.isBlank(esQuery)) {
            return MAPPER.createObjectNode().set("match_all", MAPPER.createObjectNode());
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(esQuery);
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_sync_es_query_invalid", e instanceof com.fasterxml.jackson.core.JsonProcessingException je ? je.getOriginalMessage() : e.getMessage()));
            return null;
        }
        if (node == null || !node.isObject() || node.isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_es_query_invalid", esQuery));
        }
        if (node.has("query")) {
            JsonNode query = node.get("query");
            if (!query.isObject()) {
                DEException.throwException(Translator.get("ee_sync_es_query_invalid", esQuery));
            }
            return query;
        }
        return node;
    }

    /**
     * 构造检索请求体
     */
    static ObjectNode searchBody(Source source, List<String> columns, String incrementColumn, Object incrementValue, int size) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("size", size);
        body.putArray("sort").add("_doc");
        List<String> sourceFields = columns.stream().filter(c -> !ID_FIELD.equals(c)).distinct().toList();
        if (sourceFields.isEmpty()) {
            body.put("_source", false);
        } else {
            ArrayNode includes = body.putArray("_source");
            sourceFields.forEach(includes::add);
        }
        JsonNode query = userQuery(source.getEsQuery());
        if (StringUtils.isNotBlank(incrementColumn) && incrementValue != null) {
            ObjectNode bool = MAPPER.createObjectNode();
            ArrayNode filter = bool.putObject("bool").putArray("filter");
            filter.add(query);
            ObjectNode range = filter.addObject().putObject("range").putObject(incrementColumn);
            Long millis = epochMillis(incrementValue);
            if (millis != null) {
                range.put("gt", millis);
                range.put("format", "epoch_millis");
            } else if (incrementValue instanceof BigDecimal decimal) {
                range.put("gt", decimal);
            } else if (incrementValue instanceof Number number) {
                range.put("gt", new BigDecimal(number.toString()));
            } else {
                range.put("gt", incrementValue.toString());
            }
            body.set("query", bool);
        } else {
            body.set("query", query);
        }
        return body;
    }

    private static Long epochMillis(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        return null;
    }

    @Override
    public String describe(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn, Object incrementValue) {
        return "POST /" + source.getTables() + "/_search?scroll=" + SCROLL_KEEP_ALIVE + " "
                + searchBody(source, columns, incrementColumn, incrementValue, PAGE_SIZE);
    }

    @Override
    public void read(DatasourceConfiguration cfg, Source source, List<String> columns, String incrementColumn, Object incrementValue,
                     SyncCancellation cancellation, RowHandler handler) throws Exception {
        String index = source.getTables();
        if (StringUtils.isBlank(index)) {
            DEException.throwException(Translator.get("ee_sync_task_source_table_empty"));
        }
        try (SyncHttp http = http(cfg)) {
            Map<String, String> types = new LinkedHashMap<>();
            for (EsField field : mapping(http, cfg, index, cancellation)) {
                types.put(field.path(), field.esType());
            }
            ObjectNode body = searchBody(source, columns, incrementColumn, incrementValue, PAGE_SIZE);
            JsonNode page = call(http, "POST", cfg.getUrl() + "/" + encode(index) + "/_search?scroll=" + SCROLL_KEEP_ALIVE,
                    body.toString(), cancellation);
            List<String> scrollIds = new ArrayList<>();
            try {
                while (true) {
                    String scrollId = page.path("_scroll_id").asText(null);
                    if (scrollId != null && !scrollIds.contains(scrollId)) {
                        scrollIds.add(scrollId);
                    }
                    JsonNode hits = page.path("hits").path("hits");
                    if (!hits.isArray() || hits.isEmpty()) {
                        break;
                    }
                    for (JsonNode hit : hits) {
                        Object[] row = new Object[columns.size()];
                        JsonNode doc = hit.path("_source");
                        for (int i = 0; i < columns.size(); i++) {
                            String column = columns.get(i);
                            if (ID_FIELD.equals(column)) {
                                row[i] = hit.path(ID_FIELD).asText(null);
                            } else {
                                row[i] = convert(extract(doc, column), types.get(column));
                            }
                        }
                        handler.accept(row);
                    }
                    if (hits.size() < PAGE_SIZE || scrollId == null || cancellation.isCancelled()) {
                        break;
                    }
                    ObjectNode next = MAPPER.createObjectNode().put("scroll", SCROLL_KEEP_ALIVE).put("scroll_id", scrollId);
                    page = call(http, "POST", cfg.getUrl() + "/_search/scroll", next.toString(), cancellation);
                }
            } finally {
                clearScroll(http, cfg, scrollIds);
            }
        }
    }

    private void clearScroll(SyncHttp http, DatasourceConfiguration cfg, List<String> scrollIds) {
        if (scrollIds.isEmpty()) {
            return;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode ids = body.putArray("scroll_id");
            scrollIds.forEach(ids::add);
            http.json("DELETE", cfg.getUrl() + "/_search/scroll", body.toString(), null);
        } catch (Exception e) {
            logger.warn("clear es scroll failed: " + e.getMessage());
        }
    }

    /**
     * 按路径取值：兼容嵌套对象与字面量带 . 的键；对象数组按元素收集
     */
    static JsonNode extract(JsonNode node, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            ArrayNode values = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                JsonNode value = extract(item, path);
                if (value != null) {
                    if (value.isArray()) {
                        value.forEach(values::add);
                    } else {
                        values.add(value);
                    }
                }
            }
            return values.isEmpty() ? null : values;
        }
        if (!node.isObject()) {
            return null;
        }
        if (node.has(path)) {
            return node.get(path);
        }
        int dot = path.indexOf('.');
        while (dot > 0) {
            String head = path.substring(0, dot);
            if (node.has(head)) {
                JsonNode value = extract(node.get(head), path.substring(dot + 1));
                if (value != null) {
                    return value;
                }
            }
            dot = path.indexOf('.', dot + 1);
        }
        return null;
    }

    /**
     * JSON 值 → 写入目标的 Java 值
     */
    static Object convert(JsonNode value, String esType) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String type = StringUtils.defaultString(esType).toLowerCase(Locale.ROOT);
        if (value.isArray()) {
            if (value.size() == 1 && !"nested".equals(type) && !"object".equals(type) && !"flattened".equals(type)) {
                return convert(value.get(0), esType);
            }
            return value.isEmpty() ? null : value.toString();
        }
        if (value.isObject()) {
            return value.toString();
        }
        if (STRING_TYPES.contains(type)) {
            return value.asText();
        }
        return switch (type) {
            case "long", "integer", "short", "byte" -> value.isIntegralNumber() ? (Object) value.asLong()
                    : value.isNumber() ? (Object) (long) value.asDouble() : parseNumber(value.asText(), true);
            case "unsigned_long" -> value.isNumber() ? value.decimalValue() : parseNumber(value.asText(), false);
            case "double", "float", "half_float", "scaled_float" -> value.isNumber() ? (Object) value.asDouble() : parseNumber(value.asText(), false);
            case "boolean" -> value.isBoolean() ? value.asBoolean() : parseBoolean(value.asText());
            case "date", "date_nanos" -> parseDate(value);
            default -> value.isNumber() ? value.numberValue() : value.asText();
        };
    }

    private static Object parseNumber(String text, boolean integral) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(text.trim());
            return integral ? (Object) decimal.longValue() : decimal;
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private static Object parseBoolean(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        return "true".equalsIgnoreCase(text.trim()) || "1".equals(text.trim());
    }

    /**
     * 日期：epoch 毫秒或 ISO / yyyy-MM-dd HH:mm:ss 文本；无时区时按 UTC（与 ES 一致），统一转为服务器时区的时间戳。
     * 无法解析的自定义格式保留原文。
     */
    static Object parseDate(JsonNode value) {
        if (value.isNumber()) {
            return new Timestamp(value.asLong());
        }
        String text = StringUtils.trimToEmpty(value.asText());
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("-?\\d{11,}")) {
            return new Timestamp(Long.parseLong(text));
        }
        try {
            var parsed = LOCAL_DATE_TIME.parseBest(text, OffsetDateTime::from, LocalDateTime::from, LocalDate::from);
            Instant instant;
            if (parsed instanceof OffsetDateTime odt) {
                instant = odt.toInstant();
            } else if (parsed instanceof LocalDateTime ldt) {
                instant = ldt.toInstant(ZoneOffset.UTC);
            } else {
                instant = ((LocalDate) parsed).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Timestamp.from(instant);
        } catch (Exception e) {
            return text;
        }
    }

    // ------------------------------------------------------------------ SyncDialect（仅源端，JDBC 能力不适用）

    private <T> T jdbcUnsupported() {
        throw new UnsupportedOperationException(Translator.get("ee_sync_es_jdbc_unsupported"));
    }

    @Override
    public Connection open(DatasourceConfiguration configuration) {
        return jdbcUnsupported();
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String qualified(DatasourceConfiguration configuration, String table) {
        return table;
    }

    @Override
    public String selectSql(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn) {
        return describe(configuration, source, columns, incrementColumn, null);
    }

    @Override
    public boolean tableExists(Connection connection, DatasourceConfiguration configuration, String table) {
        return jdbcUnsupported();
    }

    @Override
    public void dropTable(Connection connection, DatasourceConfiguration configuration, String table) {
        jdbcUnsupported();
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        return jdbcUnsupported();
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) {
        jdbcUnsupported();
    }

    @Override
    public String tempTableName(String table) {
        return table;
    }

    @Override
    public String insertSql(DatasourceConfiguration configuration, String table, List<String> columns) {
        return jdbcUnsupported();
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) {
        return jdbcUnsupported();
    }

    @Override
    public Object maxValue(Connection connection, DatasourceConfiguration configuration, String table, String column) {
        return jdbcUnsupported();
    }

    @Override
    public int nullType() {
        return Types.NULL;
    }

    @Override
    public Object readValue(ResultSet resultSet, int index) {
        return jdbcUnsupported();
    }
}
