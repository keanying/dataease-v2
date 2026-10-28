package io.dataease.ee.sync.provider;

import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.provider.SinkKeyPolicy;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Apache Doris 目标端：MySQL 协议执行 DDL，数据优先通过 Stream Load（FE HTTP 端口）写入，不可用时回退 JDBC 批量写入。
 *
 * <p>建表使用 Doris 的数据模型：配置了 Key 时为 UNIQUE KEY 模型（重复写入即更新），否则为 DUPLICATE 模型；
 * 分区按任务 targetProperty（{@link DorisPartition}）生成。
 * 若连接的服务端并非 Doris（例如 MySQL 协议兼容库），自动回退为 MySQL 方言建表、替换与 JDBC 写入。</p>
 */
public class DorisSyncProvider extends MysqlSyncProvider {

    private static final List<String> TYPES = List.of("BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "FLOAT",
            "DOUBLE", "DECIMAL", "DATE", "DATETIME", "CHAR", "VARCHAR", "STRING", "JSON");

    private static final List<String> UNSUPPORTED_KEY = List.of("FLOAT", "DOUBLE", "STRING", "JSON");

    private static final List<String> INDEX_TYPES = List.of("BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT",
            "DECIMAL", "DATE", "DATETIME", "CHAR", "VARCHAR", "STRING");

    private static final Map<String, Boolean> SERVER_IS_DORIS = new ConcurrentHashMap<>();

    public static final String PARAM_FE_PORT = "fePort";
    public static final String PARAM_BE_IP = "beIp";
    public static final String PARAM_BE_PORT = "bePort";
    public static final String PARAM_STREAM_LOAD = "streamLoad";
    public static final String PARAM_HTTP_SCHEME = "httpScheme";
    public static final int DEFAULT_FE_HTTP_PORT = 8030;

    public DorisSyncProvider() {
        super("doris");
    }

    @Override
    public List<String> getSchema(DatasourceRequest request) {
        return new ArrayList<>();
    }

    @Override
    public DatasourceConfiguration configuration(String configurationJson) {
        DatasourceConfiguration cfg = super.configuration(configurationJson);
        Map<String, Object> raw = io.dataease.utils.JsonUtil.parseObject(configurationJson,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        if (cfg.getParameters() == null) {
            cfg.setParameters(new java.util.HashMap<>());
        }
        Object replication = raw == null ? null : raw.get("replication");
        if (replication != null && StringUtils.isNotBlank(replication.toString())) {
            cfg.getParameters().put("replication", replication.toString().replaceAll("[^0-9]", ""));
        }
        // Stream Load 使用的 FE / BE HTTP 地址
        for (String key : List.of(PARAM_FE_PORT, PARAM_BE_IP, PARAM_BE_PORT, PARAM_STREAM_LOAD, PARAM_HTTP_SCHEME)) {
            Object value = raw == null ? null : raw.get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                cfg.getParameters().put(key, value.toString().trim());
            }
        }
        return cfg;
    }

    // ------------------------------------------------------------------ Stream Load

    /**
     * 是否允许 Stream Load：全局开关 {@code dataease.ee.sync.doris.stream-load}（默认 true）且数据源未关闭（streamLoad=false）
     */
    public boolean streamLoadEnabled(DatasourceConfiguration configuration) {
        String configured = configuration.getParameters() == null ? null : configuration.getParameters().get(PARAM_STREAM_LOAD);
        if (configured != null && "false".equalsIgnoreCase(configured.trim())) {
            return false;
        }
        try {
            if (io.dataease.utils.CommonBeanFactory.getApplicationContext() != null) {
                return io.dataease.utils.CommonBeanFactory.getApplicationContext().getEnvironment()
                        .getProperty("dataease.ee.sync.doris.stream-load", Boolean.class, true);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /**
     * 创建 Stream Load 客户端；不可用（未启用或服务端不是 Doris）时返回 null，由引擎使用 JDBC 批量写入
     */
    public DorisStreamLoader streamLoader(Connection connection, DatasourceConfiguration configuration) {
        if (!streamLoadEnabled(configuration) || !isDoris(connection, configuration)) {
            return null;
        }
        Map<String, String> params = configuration.getParameters() == null ? Map.of() : configuration.getParameters();
        int fePort = intParam(params.get(PARAM_FE_PORT), DEFAULT_FE_HTTP_PORT);
        Integer bePort = params.get(PARAM_BE_PORT) == null ? null : intParam(params.get(PARAM_BE_PORT), 0);
        String scheme = "https".equalsIgnoreCase(params.get(PARAM_HTTP_SCHEME)) ? "https" : "http";
        int timeout = Math.max(configuration.getQueryTimeout(), 600) * 1000;
        return new DorisStreamLoader(scheme + "://" + hostForUrl(configuration.getHost()) + ":" + fePort, params.get(PARAM_BE_IP),
                bePort, configuration.getDataBase(), configuration.getUsername(), configuration.getPassword(), timeout);
    }

    private static String hostForUrl(String host) {
        String h = StringUtils.trimToEmpty(host);
        return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h;
    }

    private static int intParam(String value, int fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            int v = (int) Double.parseDouble(value.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Doris 无二进制类型（映射为 STRING），二进制值按 Base64 文本写入（与 Stream Load 一致）
     */
    @Override
    public Object writeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return TYPES;
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of("CHAR", "VARCHAR", "DECIMAL");
    }

    @Override
    public List<String> unsupportedKeyFieldTypes(DatasourceRequest request) {
        return UNSUPPORTED_KEY;
    }

    @Override
    public String fieldIndexType(DatasourceRequest request) {
        return "INVERTED";
    }

    @Override
    public List<String> supportedIndexFieldTypes(DatasourceRequest request) {
        return INDEX_TYPES;
    }

    @Override
    public SinkKeyPolicy keyPolicy(Target target) {
        return SinkKeyPolicy.OPTIONAL;
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return switch (kind) {
            case BOOL -> MappedType.of("BOOLEAN");
            case INT8 -> MappedType.of("TINYINT");
            case INT16 -> MappedType.of("SMALLINT");
            case INT32 -> MappedType.of("INT");
            case INT64 -> MappedType.of("BIGINT");
            case UINT64 -> MappedType.of("LARGEINT");
            case FLOAT -> MappedType.of("FLOAT");
            case DOUBLE -> MappedType.of("DOUBLE");
            case DECIMAL -> decimal("DECIMAL", size, scale, 38, 18);
            case CHAR, VARCHAR, UUID, BIT, TIME -> {
                // Doris 长度单位为字节，UTF-8 下按 3 倍预留
                int len = size <= 0 ? 255 * 3 : size * 3;
                yield len <= 65533 ? MappedType.of("VARCHAR", len) : MappedType.of("STRING");
            }
            case TEXT, BINARY, BLOB -> MappedType.of("STRING");
            case JSON -> MappedType.of("JSON");
            case DATE -> MappedType.of("DATE");
            case DATETIME -> MappedType.of("DATETIME");
            case DATETIME_TZ -> MappedType.of("DATETIME").withMessage(io.dataease.i18n.Translator.get("ee_sync_type_timezone_dropped"));
            case YEAR -> MappedType.of("SMALLINT");
            default -> MappedType.of(UNKNOWN);
        };
    }

    /**
     * 判断服务端是否为 Doris（version_comment 含 Doris）
     */
    protected boolean isDoris(Connection connection, DatasourceConfiguration configuration) {
        if (connection == null) {
            return true;
        }
        String key = configuration.getJdbc();
        Boolean cached = key == null ? null : SERVER_IS_DORIS.get(key);
        if (cached != null) {
            return cached;
        }
        boolean doris = false;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT @@version_comment")) {
            if (rs.next()) {
                String comment = StringUtils.defaultString(rs.getString(1)).toLowerCase(Locale.ROOT);
                doris = comment.contains("doris") || comment.contains("selectdb");
            }
        } catch (SQLException e) {
            LogUtil.warn("detect doris failed: " + e.getMessage());
            doris = true;
        }
        if (key != null) {
            SERVER_IS_DORIS.put(key, doris);
        }
        return doris;
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        if (!isDoris(connection, configuration)) {
            Target mysqlTarget = new Target(target);
            mysqlTarget.setFieldList(target.getFieldList().stream().map(this::toMysqlField).toList());
            return List.of(mysqlCreateTable(configuration, table, mysqlTarget));
        }
        List<TableFieldDTO> keys = keyFields(target);
        List<TableFieldDTO> ordered = new ArrayList<>(keys);
        target.getFieldList().stream().filter(f -> !f.isFieldPk()).forEach(ordered::add);
        List<String> parts = new ArrayList<>();
        for (TableFieldDTO field : ordered) {
            String col = quote(field.getFieldName()) + " " + columnType(field) + (field.isFieldPk() ? " NOT NULL" : " NULL");
            if (StringUtils.isNotBlank(field.getRemarks())) {
                col += " COMMENT '" + escapeLiteral(field.getRemarks()) + "'";
            }
            parts.add(col);
        }
        int i = 0;
        for (TableFieldDTO field : ordered) {
            if (field.isFieldIndex()) {
                parts.add("INDEX " + quote("idx_" + (i++) + "_" + StringUtils.left(field.getFieldName(), 50)) + " ("
                        + quote(field.getFieldName()) + ") USING INVERTED");
            }
        }
        DorisPartition partition = DorisPartition.parse(target);
        if (partition != null) {
            partition.validate(target);
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualified(configuration, table))
                .append(" (\n  ").append(String.join(",\n  ", parts)).append("\n) ENGINE=OLAP\n");
        String buckets = partition != null && partition.dynamic() ? String.valueOf(partition.bucketsOrDefault())
                : partition != null && partition.buckets() != null ? String.valueOf(partition.buckets()) : "AUTO";
        String distribution;
        if (!keys.isEmpty()) {
            String keyCols = keys.stream().map(f -> quote(f.getFieldName())).collect(Collectors.joining(", "));
            sql.append("UNIQUE KEY(").append(keyCols).append(")\n");
            distribution = "DISTRIBUTED BY HASH(" + keyCols + ") BUCKETS " + buckets;
        } else {
            distribution = "DISTRIBUTED BY RANDOM BUCKETS " + buckets;
        }
        if (StringUtils.isNotBlank(target.getRemarks())) {
            sql.append("COMMENT '").append(escapeLiteral(target.getRemarks())).append("'\n");
        }
        if (partition != null) {
            sql.append(partition.clause(this::quote, fieldType(target, partition.column()))).append("\n");
        }
        sql.append(distribution).append("\n");
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        String replication = replication(connection, configuration);
        if (replication != null) {
            properties.put("replication_num", replication);
        }
        if (partition != null) {
            properties.putAll(partition.properties());
        }
        if (!properties.isEmpty()) {
            sql.append("PROPERTIES (\n  ").append(properties.entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\" = \"" + escapeLiteral(e.getValue()).replace("\"", "") + "\"")
                    .collect(Collectors.joining(",\n  "))).append("\n)");
        }
        return List.of(sql.toString());
    }

    private static String fieldType(Target target, String column) {
        return target.getFieldList().stream().filter(f -> StringUtils.equals(f.getFieldName(), column))
                .map(f -> StringUtils.upperCase(StringUtils.substringBefore(StringUtils.trimToEmpty(f.getFieldType()), "(")))
                .findFirst().orElse("");
    }

    @Override
    public void validateTarget(Target target) {
        super.validateTarget(target);
        DorisPartition partition = DorisPartition.parse(target);
        if (partition != null) {
            partition.validate(target);
        }
    }

    private TableFieldDTO toMysqlField(TableFieldDTO field) {
        TableFieldDTO copy = new TableFieldDTO();
        org.springframework.beans.BeanUtils.copyProperties(field, copy);
        String t = StringUtils.upperCase(StringUtils.substringBefore(field.getFieldType(), "("));
        switch (StringUtils.defaultString(t)) {
            case "BOOLEAN" -> copy.setFieldType("TINYINT");
            case "LARGEINT" -> {
                copy.setFieldType("DECIMAL");
                copy.setFieldSize(39);
                copy.setFieldPrecision(0);
            }
            case "STRING" -> copy.setFieldType(field.isFieldPk() ? "VARCHAR" : "LONGTEXT");
            case "VARCHAR" -> {
                if (field.getFieldSize() > 4000) {
                    copy.setFieldType(field.isFieldPk() ? "VARCHAR" : "LONGTEXT");
                }
                if (field.isFieldPk()) {
                    copy.setFieldSize(Math.min(Math.max(field.getFieldSize(), 1), 768));
                }
            }
            default -> {
            }
        }
        if (field.isFieldPk() && "VARCHAR".equals(copy.getFieldType()) && copy.getFieldSize() <= 0) {
            copy.setFieldSize(255);
        }
        return copy;
    }

    private String replication(Connection connection, DatasourceConfiguration configuration) {
        Object configured = configuration.getParameters() == null ? null : configuration.getParameters().get("replication");
        String json = null;
        if (configured != null && StringUtils.isNotBlank(configured.toString())) {
            json = configured.toString();
        }
        if (json != null) {
            return json;
        }
        if (connection == null) {
            return null;
        }
        int alive = 0;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SHOW BACKENDS")) {
            while (rs.next()) {
                alive++;
            }
        } catch (SQLException e) {
            return null;
        }
        return alive <= 0 ? null : String.valueOf(Math.min(alive, 3));
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException {
        if (!isDoris(connection, configuration)) {
            super.replaceTable(connection, configuration, tempTable, table);
            return;
        }
        if (tableExists(connection, configuration, table)) {
            execute(connection, "ALTER TABLE " + qualified(configuration, table) + " REPLACE WITH TABLE "
                    + quote(tempTable) + " PROPERTIES('swap' = 'false')");
        } else {
            execute(connection, "ALTER TABLE " + qualified(configuration, tempTable) + " RENAME " + quote(table));
        }
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) throws SQLException {
        if (isDoris(connection, configuration)) {
            // UNIQUE KEY 模型写入即覆盖
            return insertSql(configuration, table, columns);
        }
        return super.upsertSql(connection, configuration, table, columns, keys);
    }
}
