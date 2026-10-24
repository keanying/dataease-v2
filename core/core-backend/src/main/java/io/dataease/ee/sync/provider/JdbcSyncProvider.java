package io.dataease.ee.sync.provider;

import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.vo.Configuration;
import io.dataease.extensions.sync.datatype.StandardDataType;
import io.dataease.extensions.sync.model.TableDTO;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.ConnectionObj;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.model.task.TaskInfoVO;
import io.dataease.extensions.sync.provider.SinkKeyPolicy;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.provider.SourceProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.extensions.sync.utils.JdbcDataTypeNormalizer;
import io.dataease.extensions.sync.utils.ProviderUtil;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内置 JDBC 同步数据源提供者基类：同时实现源端与目标端能力，具体数据库通过子类定制方言。
 */
public abstract class JdbcSyncProvider extends SyncProvider implements SourceProvider, SinkProvider, SyncDialect {

    /**
     * 字段逻辑类型：标准类型 → 逻辑类型 → 目标库类型
     */
    public enum Kind {
        BOOL, INT8, INT16, INT32, INT64, UINT64, FLOAT, DOUBLE, DECIMAL, CHAR, VARCHAR, TEXT, JSON,
        DATE, TIME, DATETIME, DATETIME_TZ, YEAR, BINARY, BLOB, BIT, UUID, UNKNOWN
    }

    /**
     * 映射结果
     */
    public record MappedType(String type, int size, int scale, String message) {
        public static MappedType of(String type) {
            return new MappedType(type, 0, 0, null);
        }

        public static MappedType of(String type, int size) {
            return new MappedType(type, size, 0, null);
        }

        public static MappedType of(String type, int size, int scale) {
            return new MappedType(type, size, scale, null);
        }

        public MappedType withMessage(String msg) {
            return new MappedType(type, size, scale, msg);
        }
    }

    protected static final String UNKNOWN = "UNKNOWN";

    private final String type;

    protected JdbcSyncProvider(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    @Override
    public String getProviderName() {
        return type;
    }

    // ------------------------------------------------------------------ 方言定制点

    /**
     * 使用核心数据源类型计算 jdbc 地址与驱动（含 JDBC 参数安全校验）
     */
    protected abstract Configuration coreConfiguration(String configurationJson);

    protected abstract String quotePrefix();

    protected abstract String quoteSuffix();

    /**
     * 元数据查询使用的 catalog
     */
    protected String metaCatalog(DatasourceConfiguration configuration) {
        return null;
    }

    /**
     * 元数据查询使用的 schema
     */
    protected String metaSchema(DatasourceConfiguration configuration) {
        return StringUtils.trimToNull(configuration.getSchema());
    }

    /**
     * 是否需要（支持）schema
     */
    protected boolean schemaRequired() {
        return false;
    }

    /**
     * 是否可作为目标端
     */
    protected boolean sinkSupported() {
        return true;
    }

    /**
     * 目标库类型列表
     */
    protected abstract List<String> sinkDataTypes();

    /**
     * 需要长度（及精度）的类型
     */
    protected abstract Set<String> sizedTypes();

    /**
     * 长度、精度都需要的数值类型
     */
    protected Set<String> decimalTypes() {
        return Set.of("DECIMAL", "NUMERIC", "NUMBER");
    }

    protected abstract MappedType mapKind(Kind kind, int size, int scale);

    protected int maxIdentifierLength() {
        return 64;
    }

    protected String testQuery() {
        return "SELECT 1";
    }

    // ------------------------------------------------------------------ SyncProvider

    @Override
    public DatasourceConfiguration configuration(String configurationJson) {
        if (StringUtils.isBlank(configurationJson)) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        DatasourceConfiguration cfg = JsonUtil.parseObject(configurationJson, DatasourceConfiguration.class);
        checkRequired(cfg);
        Configuration core = coreConfiguration(configurationJson);
        cfg.setDriver(core.getDriver());
        cfg.setJdbc(core.getJdbc());
        return cfg;
    }

    protected void checkRequired(DatasourceConfiguration cfg) {
        if (StringUtils.isBlank(cfg.getHost()) || cfg.getPort() == null || cfg.getPort() <= 0
                || StringUtils.isBlank(cfg.getDataBase())) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        if (schemaRequired() && StringUtils.isBlank(cfg.getSchema())) {
            DEException.throwException(Translator.get("ee_sync_ds_schema_required"));
        }
    }

    @Override
    public Connection open(DatasourceConfiguration configuration) {
        return SyncJdbcSupport.connect(configuration.getDriver(), configuration.getJdbc(),
                configuration.getUsername(), configuration.getPassword());
    }

    @Override
    public ConnectionObj getConnection(DatasourceConfiguration configuration) throws DEException {
        ConnectionObj obj = new ConnectionObj();
        obj.setConnection(open(configuration));
        obj.setConfiguration(configuration);
        return obj;
    }

    @Override
    public List<String> getSchema(DatasourceRequest request) throws DEException {
        List<String> schemas = new ArrayList<>();
        DatasourceConfiguration cfg = JsonUtil.parseObject(request.getConfiguration(), DatasourceConfiguration.class);
        if (cfg == null) {
            return schemas;
        }
        // 获取 schema 时还未填写 schema，跳过必填校验
        Configuration core = coreConfiguration(request.getConfiguration());
        try (Connection con = SyncJdbcSupport.connect(core.getDriver(), core.getJdbc(), cfg.getUsername(), cfg.getPassword());
             ResultSet rs = con.getMetaData().getSchemas()) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return schemas;
    }

    @Override
    public String checkStatus(DatasourceRequest request) throws DEException {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        try (Connection con = open(cfg); Statement st = con.createStatement()) {
            st.setQueryTimeout(Math.max(cfg.getQueryTimeout(), 5));
            st.execute(testQuery());
        } catch (SQLException e) {
            DEException.throwException(Translator.get("ee_sync_connect_failed", SyncJdbcSupport.rootMessage(e)));
        }
        return "Success";
    }

    @Override
    public String validate(DatasourceRequest request) throws DEException {
        checkConfiguration(request);
        return checkStatus(request);
    }

    @Override
    public void checkConfiguration(DatasourceRequest request) throws DEException {
        configuration(request.getConfiguration());
    }

    // ------------------------------------------------------------------ 公共工具

    @Override
    public String quote(String identifier) {
        String prefix = quotePrefix();
        String suffix = quoteSuffix();
        String escaped = identifier.replace(suffix, suffix + suffix);
        return prefix + escaped + suffix;
    }

    @Override
    public String qualified(DatasourceConfiguration configuration, String table) {
        String schema = metaSchema(configuration);
        return schema == null ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    public int nullType() {
        return Types.NULL;
    }

    @Override
    public Object readValue(ResultSet resultSet, int index) throws SQLException {
        return SyncProviderDefaults.readValue(resultSet, index);
    }

    @Override
    public String tempTableName(String table) {
        String name = TEMP_TABLE_PREFIX + table;
        return name.length() > maxIdentifierLength() ? name.substring(0, maxIdentifierLength()) : name;
    }

    protected void execute(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------ SourceProvider

    @Override
    public List<TableDTO> getTables(DatasourceRequest request) {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        List<TableDTO> tables = new ArrayList<>();
        try (Connection con = open(cfg);
             ResultSet rs = con.getMetaData().getTables(metaCatalog(cfg), metaSchema(cfg), "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                TableDTO dto = new TableDTO();
                dto.setDatasourceId(request.getId());
                dto.setName(rs.getString("TABLE_NAME"));
                dto.setRemark(rs.getString("REMARKS"));
                tables.add(dto);
            }
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return tables;
    }

    @Override
    public List<TableFieldDTO> fetchTableField(DatasourceRequest request) throws DEException {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        List<TableFieldDTO> fields = new ArrayList<>();
        try (Connection con = open(cfg)) {
            DatabaseMetaData md = con.getMetaData();
            String catalog = metaCatalog(cfg);
            String schema = metaSchema(cfg);
            try (ResultSet rs = md.getColumns(catalog, schema, request.getTable(), null)) {
                while (rs.next()) {
                    // 表名参数是模式匹配（_ 为通配符），需精确过滤
                    if (!StringUtils.equals(rs.getString("TABLE_NAME"), request.getTable())) {
                        continue;
                    }
                    int size = rs.getInt("COLUMN_SIZE");
                    int scale = rs.getInt("DECIMAL_DIGITS");
                    fields.add(buildField(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE"),
                            size, scale, size, rs.getString("REMARKS")));
                }
            }
            ProviderUtil.markPrimaryKeyFields(con, catalog, schema, request.getTable(), fields);
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        if (fields.isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_table_no_field", request.getTable()));
        }
        return fields;
    }

    @Override
    public List<TableFieldDTO> getFieldsBySql(DatasourceRequest request) {
        DatasourceConfiguration cfg = configuration(request.getConfiguration());
        String sql = checkQuery(request.getQuery());
        List<TableFieldDTO> fields = new ArrayList<>();
        try (Connection con = open(cfg);
             PreparedStatement ps = con.prepareStatement("SELECT * FROM (" + sql + ") " + quote("de_sync_q") + " WHERE 1 = 0")) {
            ps.setQueryTimeout(Math.max(cfg.getQueryTimeout(), 30));
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    fields.add(buildField(md.getColumnLabel(i), md.getColumnTypeName(i), md.getColumnType(i),
                            md.getPrecision(i), Math.max(md.getScale(i), 0), md.getColumnDisplaySize(i), null));
                }
            }
        } catch (SQLException e) {
            DEException.throwException(Translator.get("ee_sync_sql_invalid", SyncJdbcSupport.rootMessage(e)));
        }
        return fields;
    }

    /**
     * 只允许查询语句，去掉结尾分号
     */
    public static String checkQuery(String query) {
        String sql = StringUtils.trimToEmpty(query);
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        String head = sql.toUpperCase(Locale.ROOT);
        if (sql.isEmpty() || !(head.startsWith("SELECT") || head.startsWith("WITH") || head.startsWith("("))) {
            DEException.throwException(Translator.get("ee_sync_sql_only_select"));
        }
        return sql;
    }

    protected TableFieldDTO buildField(String name, String typeName, int jdbcType, int size, int scale, int displaySize, String remarks) {
        StandardDataType std = JdbcDataTypeNormalizer.normalize(typeName, jdbcType, size, scale, displaySize);
        TableFieldDTO field = new TableFieldDTO();
        field.setFieldSource(name);
        field.setFieldName(name);
        field.setFieldSourceType(typeName);
        field.setFieldSourceStandardType(std.name());
        field.setFieldType(std.name());
        field.setFieldSize(Math.max(size, 0));
        field.setFieldPrecision(Math.max(scale, 0));
        field.setRemarks(remarks);
        return field;
    }

    @Override
    public String selectSql(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn) {
        String cols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String from;
        if (StringUtils.equals(source.getTableExtract(), "1")) {
            from = "(" + checkQuery(source.getQuery()) + ") " + quote("de_sync_q");
        } else {
            from = qualified(configuration, source.getTables());
        }
        String sql = "SELECT " + cols + " FROM " + from;
        if (StringUtils.isNotBlank(incrementColumn)) {
            sql += " WHERE " + quote(incrementColumn) + " > ?";
        }
        return sql;
    }

    @Override
    public String generatorSourceConfig(TaskInfoVO taskInfoVO) {
        Map<String, Object> jdbc = new LinkedHashMap<>();
        jdbc.put("plugin_name", "Jdbc");
        jdbc.put("result_table_name", "fake");
        jdbc.put("query", taskInfoVO.getSource() == null ? null : taskInfoVO.getSource().getQuery());
        return JsonUtil.toJSONString(Map.of("Jdbc", jdbc)).toString();
    }

    @Override
    public String appendIncrementConditions(TaskInfoVO taskInfo, String jobParameter) {
        return jobParameter;
    }

    @Override
    public StandardDataType dataTypeToStandardDataType(String sourceType) {
        return JdbcDataTypeNormalizer.normalizeNativeType(sourceType);
    }

    // ------------------------------------------------------------------ SinkProvider

    protected DatasourceConfiguration requestConfiguration(DatasourceRequest request) {
        return configuration(request.getConfiguration());
    }

    @Override
    public boolean dropTable(DatasourceRequest request, String tableName) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg)) {
            dropTable(con, cfg, tableName);
            return true;
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return false;
    }

    @Override
    public boolean createTable(DatasourceRequest request, String createSql) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg)) {
            for (String sql : createSql.split(SyncProviderDefaults.STATEMENT_SEPARATOR)) {
                if (StringUtils.isNotBlank(sql)) {
                    execute(con, sql);
                }
            }
            return true;
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return false;
    }

    @Override
    public boolean checkTableExists(DatasourceRequest request) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg)) {
            return tableExists(con, cfg, request.getTable());
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return false;
    }

    @Override
    public boolean tableExists(Connection connection, DatasourceConfiguration configuration, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(metaCatalog(configuration), metaSchema(configuration), table, null)) {
            while (rs.next()) {
                if (StringUtils.equals(rs.getString("TABLE_NAME"), table)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void dropTable(Connection connection, DatasourceConfiguration configuration, String table) throws SQLException {
        if (tableExists(connection, configuration, table)) {
            execute(connection, "DROP TABLE " + qualified(configuration, table));
        }
    }

    @Override
    public String getCreateTableSql(Source source, Target target) {
        DatasourceConfiguration cfg = new DatasourceConfiguration();
        if (target.getDatasource() != null && StringUtils.isNotBlank(target.getDatasource().getConfiguration())) {
            cfg = JsonUtil.parseObject(target.getDatasource().getConfiguration(), DatasourceConfiguration.class);
        }
        try {
            return String.join(SyncProviderDefaults.STATEMENT_SEPARATOR, createTableStatements(null, cfg, target.getTableName(), target));
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return null;
    }

    /**
     * 字段列定义（类型部分）
     */
    public String columnType(TableFieldDTO field) {
        String fieldType = StringUtils.trimToEmpty(field.getFieldType()).toUpperCase(Locale.ROOT);
        if (fieldType.contains("(")) {
            return fieldType;
        }
        if (sizedTypes().contains(fieldType) && field.getFieldSize() > 0) {
            if (decimalTypes().contains(fieldType)) {
                return fieldType + "(" + field.getFieldSize() + "," + Math.max(field.getFieldPrecision(), 0) + ")";
            }
            return fieldType + "(" + field.getFieldSize() + ")";
        }
        return fieldType;
    }

    /**
     * 校验目标字段：名称、类型
     */
    protected void checkTargetFields(Target target) {
        if (target.getFieldList() == null || target.getFieldList().isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_task_field_empty"));
        }
        Set<String> names = new java.util.HashSet<>();
        List<String> types = sinkDataTypes();
        for (TableFieldDTO field : target.getFieldList()) {
            if (StringUtils.isBlank(field.getFieldName())) {
                DEException.throwException(Translator.get("ee_sync_task_field_empty"));
            }
            if (!names.add(field.getFieldName().toLowerCase(Locale.ROOT))) {
                DEException.throwException(Translator.get("ee_sync_task_field_duplicate", field.getFieldName()));
            }
            String fieldType = StringUtils.substringBefore(StringUtils.trimToEmpty(field.getFieldType()), "(").trim();
            if (StringUtils.isBlank(fieldType) || UNKNOWN.equalsIgnoreCase(fieldType)
                    || types.stream().noneMatch(t -> t.equalsIgnoreCase(fieldType))) {
                DEException.throwException(Translator.get("ee_sync_task_field_type_invalid", field.getFieldName(), field.getFieldType()));
            }
            if (field.isFieldPk() && unsupportedKeyFieldTypes(null).stream().anyMatch(t -> t.equalsIgnoreCase(fieldType))) {
                DEException.throwException(Translator.get("ee_sync_task_key_type_invalid", field.getFieldName()));
            }
        }
    }

    protected String escapeLiteral(String text) {
        return StringUtils.defaultString(text).replace("'", "''");
    }

    protected List<TableFieldDTO> keyFields(Target target) {
        return target.getFieldList().stream().filter(TableFieldDTO::isFieldPk).toList();
    }

    @Override
    public String getTargetTableIncrementFieldMaxValue(DatasourceRequest request, TaskInfoVO incrementTaskInfo) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg)) {
            Object value = maxValue(con, cfg, incrementTaskInfo.getTarget().getTableName(), incrementTaskInfo.getTarget().getIncrementField());
            return value == null ? null : value.toString();
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return null;
    }

    @Override
    public Object maxValue(Connection connection, DatasourceConfiguration configuration, String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(" + quote(column) + ") FROM " + qualified(configuration, table))) {
            return rs.next() ? readValue(rs, 1) : null;
        }
    }

    @Override
    public boolean createTableLike(DatasourceRequest request, String createTableSql) {
        return createTable(request, createTableSql);
    }

    @Override
    public boolean replaceTable(DatasourceRequest request, String tableName) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg)) {
            replaceTable(con, cfg, tempTableName(tableName), tableName);
            return true;
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return false;
    }

    @Override
    public boolean checkTableHasData(DatasourceRequest request, String tableName) {
        DatasourceConfiguration cfg = requestConfiguration(request);
        try (Connection con = open(cfg); Statement st = con.createStatement()) {
            st.setMaxRows(1);
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + qualified(cfg, tableName))) {
                return rs.next();
            }
        } catch (SQLException e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return false;
    }

    @Override
    public boolean validateCreateTableSql(DatasourceRequest request, String createTableSql, String tableName) {
        return StringUtils.isNotBlank(createTableSql) && StringUtils.isNotBlank(tableName);
    }

    @Override
    public List<String> dataTypes(DatasourceRequest request) {
        return sinkDataTypes();
    }

    @Override
    public void dataTypeMapping(List<TableFieldDTO> sourceTableFields, DatasourceRequest source, DatasourceRequest target) {
        for (TableFieldDTO field : sourceTableFields) {
            StandardDataType std = StandardDataType.customValueOf(StringUtils.defaultIfBlank(field.getFieldSourceStandardType(), UNKNOWN));
            MappedType mapped = mapKind(kindOf(std), field.getFieldSize(), field.getFieldPrecision());
            field.setFieldType(mapped.type());
            field.setFieldSize(mapped.size());
            field.setFieldPrecision(mapped.scale());
            if (UNKNOWN.equals(mapped.type())) {
                field.setFieldMappingMessage(Translator.get("ee_sync_type_unmapped", StringUtils.defaultString(field.getFieldSourceType())));
            } else {
                field.setFieldMappingMessage(mapped.message());
            }
        }
    }

    @Override
    public void validateTarget(Target target) {
        if (!sinkSupported()) {
            DEException.throwException(Translator.get("ee_sync_sink_unsupported", type));
        }
        if (StringUtils.isBlank(target.getTableName()) || !target.getTableName().matches(io.dataease.ee.sync.SyncConstants.TABLE_NAME_REGEX)
                || target.getTableName().length() > maxIdentifierLength()) {
            DEException.throwException(Translator.get("ee_sync_task_table_name_invalid"));
        }
        checkTargetFields(target);
    }

    @Override
    public SinkKeyPolicy keyPolicy(Target target) {
        return SinkKeyPolicy.OPTIONAL;
    }

    @Override
    public String generatorSinkConfig(TaskInfoVO taskInfoVO) {
        Map<String, Object> jdbc = new LinkedHashMap<>();
        jdbc.put("plugin_name", "Jdbc");
        jdbc.put("table", taskInfoVO.getTarget() == null ? null : taskInfoVO.getTarget().getTableName());
        return JsonUtil.toJSONString(Map.of("Jdbc", jdbc)).toString();
    }

    @Override
    public String insertSql(DatasourceConfiguration configuration, String table, List<String> columns) {
        return "INSERT INTO " + qualified(configuration, table) + " ("
                + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) throws SQLException {
        return null;
    }

    // ------------------------------------------------------------------ 类型映射

    public static Kind kindOf(StandardDataType std) {
        if (std == null) {
            return Kind.UNKNOWN;
        }
        return switch (std) {
            case BOOLEAN -> Kind.BOOL;
            case TINYINT, BYTE, TINYINT_IDENTITY -> Kind.INT8;
            case TINYINT_UNSIGNED, SMALLINT, SHORT, SMALLINT_IDENTITY -> Kind.INT16;
            case SMALLINT_UNSIGNED, MEDIUMINT, MEDIUMINT_UNSIGNED, INT, INTEGER, INT_IDENTITY, INTEGER_IDENTITY -> Kind.INT32;
            case INT_UNSIGNED, INTEGER_UNSIGNED, BIGINT, LONG_INT, BIGINT_IDENTITY -> Kind.INT64;
            case BIGINT_UNSIGNED, UNSIGNED_LONG -> Kind.UINT64;
            case FLOAT, REAL, HALF_FLOAT, BINARY_FLOAT -> Kind.FLOAT;
            case DOUBLE, DOUBLE_PRECISION, BINARY_DOUBLE, SCALED_FLOAT -> Kind.DOUBLE;
            case DECIMAL, NUMERIC, DECIMAL_UNSIGNED, NUMERIC_UNSIGNED, NUMBER, MONEY, SMALLMONEY, DECFLOAT,
                 DECIMAL_IDENTITY, NUMERIC_IDENTITY -> Kind.DECIMAL;
            case CHAR, NCHAR, CHARACTER -> Kind.CHAR;
            case VARCHAR, VARCHAR2, NVARCHAR, NVARCHAR2, TINYSTRING, KEYWORD, ENUM, SET, INET, CIDR, MACADDR, IP,
                 INTERVAL, ROWID, UROWID -> Kind.VARCHAR;
            case STRING, TEXT, CLOB, TINYTEXT, MEDIUMTEXT, LONGTEXT, MEDIUMSTRING, LONGSTRING, LONG, NCLOB, NTEXT, XML,
                 LONGVARCHAR, ARRAY, OBJECT, NESTED -> Kind.TEXT;
            case JSON, JSONB -> Kind.JSON;
            case DATE -> Kind.DATE;
            case TIME -> Kind.TIME;
            case DATETIME, DATETIME2, SMALLDATETIME, TIMESTAMP -> Kind.DATETIME;
            case TIMESTAMPTZ, DATETIMEOFFSET -> Kind.DATETIME_TZ;
            case YEAR -> Kind.YEAR;
            case BINARY, VARBINARY, RAW, TINYBINARY -> Kind.BINARY;
            case BLOB, BYTEA, LONGBINARY, TINYBLOB, MEDIUMBLOB, LONGBLOB, LONG_RAW -> Kind.BLOB;
            case BIT -> Kind.BIT;
            case UUID -> Kind.UUID;
            default -> Kind.UNKNOWN;
        };
    }

    protected static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 数值精度归一：size 为总位数，scale 为小数位
     */
    protected static MappedType decimal(String typeName, int size, int scale, int maxPrecision, int maxScale) {
        if (size <= 0 || size > maxPrecision) {
            int s = clamp(scale <= 0 ? 10 : scale, 0, maxScale);
            return MappedType.of(typeName, maxPrecision, s).withMessage(Translator.get("ee_sync_type_precision_adjusted"));
        }
        int s = clamp(scale, 0, Math.min(maxScale, size));
        return MappedType.of(typeName, size, s);
    }

    protected static boolean equalsAnyIgnoreCase(String value, List<String> candidates) {
        return candidates.stream().anyMatch(c -> Objects.equals(c.toUpperCase(Locale.ROOT), StringUtils.upperCase(value)));
    }
}
