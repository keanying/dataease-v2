package io.dataease.ee.sync.provider;

import io.dataease.datasource.type.Mysql;
import io.dataease.extensions.datasource.vo.Configuration;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MySQL / MariaDB（源端、目标端），使用 MariaDB 驱动
 */
public class MysqlSyncProvider extends JdbcSyncProvider {

    private static final List<String> TYPES = List.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT", "FLOAT", "DOUBLE",
            "DECIMAL", "CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "JSON", "DATE", "TIME", "DATETIME",
            "TIMESTAMP", "YEAR", "BIT", "BINARY", "VARBINARY", "BLOB", "LONGBLOB");

    private static final List<String> UNSUPPORTED_KEY = List.of("TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "JSON", "BLOB", "LONGBLOB");

    private static final List<String> INDEX_TYPES = List.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT", "FLOAT",
            "DOUBLE", "DECIMAL", "CHAR", "VARCHAR", "DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR");

    public MysqlSyncProvider(String type) {
        super(type);
    }

    @Override
    protected Configuration coreConfiguration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, Mysql.class);
    }

    @Override
    protected String quotePrefix() {
        return "`";
    }

    @Override
    protected String quoteSuffix() {
        return "`";
    }

    @Override
    protected String metaCatalog(DatasourceConfiguration configuration) {
        return configuration.getDataBase();
    }

    @Override
    protected String metaSchema(DatasourceConfiguration configuration) {
        return null;
    }

    @Override
    public String qualified(DatasourceConfiguration configuration, String table) {
        return StringUtils.isBlank(configuration.getDataBase()) ? quote(table) : quote(configuration.getDataBase()) + "." + quote(table);
    }

    @Override
    public List<String> getSchema(DatasourceRequest request) {
        return new ArrayList<>();
    }

    @Override
    public int fetchSize() {
        // MariaDB 驱动正数 fetchSize 即流式读取
        return SyncProviderDefaults.FETCH_SIZE;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return TYPES;
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of("CHAR", "VARCHAR", "DECIMAL", "BINARY", "VARBINARY", "BIT");
    }

    @Override
    protected Set<String> decimalTypes() {
        return Set.of("DECIMAL");
    }

    @Override
    public List<String> unsupportedKeyFieldTypes(DatasourceRequest request) {
        return UNSUPPORTED_KEY;
    }

    @Override
    public String fieldIndexType(DatasourceRequest request) {
        return "BTREE";
    }

    @Override
    public List<String> supportedIndexFieldTypes(DatasourceRequest request) {
        return INDEX_TYPES;
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return switch (kind) {
            case BOOL, INT8 -> MappedType.of("TINYINT");
            case INT16 -> MappedType.of("SMALLINT");
            case INT32 -> MappedType.of("INT");
            case INT64 -> MappedType.of("BIGINT");
            case UINT64 -> MappedType.of("DECIMAL", 20, 0);
            case FLOAT -> MappedType.of("FLOAT");
            case DOUBLE -> MappedType.of("DOUBLE");
            case DECIMAL -> decimal("DECIMAL", size, scale, 65, 30);
            case CHAR -> size > 0 && size <= 255 ? MappedType.of("CHAR", size) : varchar(size);
            case VARCHAR -> varchar(size);
            case TEXT -> MappedType.of("LONGTEXT");
            case JSON -> MappedType.of("JSON");
            case DATE -> MappedType.of("DATE");
            case TIME -> MappedType.of("TIME");
            case DATETIME -> MappedType.of("DATETIME");
            case DATETIME_TZ -> MappedType.of("DATETIME").withMessage(io.dataease.i18n.Translator.get("ee_sync_type_timezone_dropped"));
            case YEAR -> MappedType.of("YEAR");
            case BINARY -> size > 0 && size <= 8000 ? MappedType.of("VARBINARY", size) : MappedType.of("LONGBLOB");
            case BLOB -> MappedType.of("LONGBLOB");
            case BIT -> MappedType.of("BIT", clamp(size, 1, 64));
            case UUID -> MappedType.of("CHAR", 36);
            default -> MappedType.of(UNKNOWN);
        };
    }

    private MappedType varchar(int size) {
        if (size <= 0) {
            return MappedType.of("VARCHAR", 255);
        }
        return size <= 4000 ? MappedType.of("VARCHAR", size) : MappedType.of("LONGTEXT");
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        return List.of(mysqlCreateTable(configuration, table, target));
    }

    protected String mysqlCreateTable(DatasourceConfiguration configuration, String table, Target target) {
        List<String> parts = new ArrayList<>();
        for (TableFieldDTO field : target.getFieldList()) {
            String col = quote(field.getFieldName()) + " " + columnType(field) + (field.isFieldPk() ? " NOT NULL" : " NULL");
            if (StringUtils.isNotBlank(field.getRemarks())) {
                col += " COMMENT '" + escapeLiteral(field.getRemarks()) + "'";
            }
            parts.add(col);
        }
        List<TableFieldDTO> keys = keyFields(target);
        if (!keys.isEmpty()) {
            parts.add("PRIMARY KEY (" + keys.stream().map(f -> quote(f.getFieldName())).collect(Collectors.joining(", ")) + ")");
        }
        int i = 0;
        for (TableFieldDTO field : target.getFieldList()) {
            if (field.isFieldIndex() && !field.isFieldPk()) {
                parts.add("KEY " + quote("idx_" + (i++) + "_" + StringUtils.left(field.getFieldName(), 50)) + " (" + quote(field.getFieldName()) + ")");
            }
        }
        String sql = "CREATE TABLE " + qualified(configuration, table) + " (\n  " + String.join(",\n  ", parts) + "\n) DEFAULT CHARSET=utf8mb4";
        if (StringUtils.isNotBlank(target.getRemarks())) {
            sql += " COMMENT='" + escapeLiteral(target.getRemarks()) + "'";
        }
        return sql;
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException {
        if (tableExists(connection, configuration, table)) {
            String old = StringUtils.left("old_" + table, 40) + "_" + Long.toString(System.currentTimeMillis(), 36);
            execute(connection, "RENAME TABLE " + qualified(configuration, table) + " TO " + qualified(configuration, old)
                    + ", " + qualified(configuration, tempTable) + " TO " + qualified(configuration, table));
            execute(connection, "DROP TABLE " + qualified(configuration, old));
        } else {
            execute(connection, "RENAME TABLE " + qualified(configuration, tempTable) + " TO " + qualified(configuration, table));
        }
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) throws SQLException {
        List<String> updates = columns.stream().filter(c -> !keys.contains(c))
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")").toList();
        if (updates.isEmpty()) {
            return insertSql(configuration, table, columns).replaceFirst("INSERT INTO", "INSERT IGNORE INTO");
        }
        return insertSql(configuration, table, columns) + " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
    }

    /**
     * 时间类型默认保留微秒（MySQL / Doris 的 DATETIME、TIME 不写精度时为 0，会截断或进位小数秒）
     */
    @Override
    public String columnType(TableFieldDTO field) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(field.getFieldType()));
        if (Set.of("DATETIME", "TIME").contains(type)) {
            return type + "(6)";
        }
        return super.columnType(field);
    }

    /**
     * MariaDB 驱动将 MySQL JSON 列读为 byte[]，转为文本
     */
    @Override
    public Object readValue(java.sql.ResultSet resultSet, int index) throws SQLException {
        Object value = super.readValue(resultSet, index);
        if (value instanceof byte[] && "JSON".equalsIgnoreCase(resultSet.getMetaData().getColumnTypeName(index))) {
            return resultSet.getString(index);
        }
        return value;
    }
}
