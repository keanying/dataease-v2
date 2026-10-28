package io.dataease.ee.sync.provider;

import io.dataease.datasource.type.Sqlserver;
import io.dataease.extensions.datasource.vo.Configuration;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL Server（源端、目标端）
 */
public class SqlServerSyncProvider extends JdbcSyncProvider {

    private static final List<String> TYPES = List.of("BIT", "TINYINT", "SMALLINT", "INT", "BIGINT", "REAL", "FLOAT", "DECIMAL",
            "NUMERIC", "MONEY", "CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "TEXT", "NTEXT", "DATE", "TIME", "DATETIME", "DATETIME2",
            "DATETIMEOFFSET", "BINARY", "VARBINARY", "UNIQUEIDENTIFIER");

    public SqlServerSyncProvider() {
        super("sqlServer");
    }

    @Override
    protected Configuration coreConfiguration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, Sqlserver.class);
    }

    @Override
    protected String quotePrefix() {
        return "[";
    }

    @Override
    protected String quoteSuffix() {
        return "]";
    }

    @Override
    protected String metaCatalog(DatasourceConfiguration configuration) {
        return configuration.getDataBase();
    }

    @Override
    protected String metaSchema(DatasourceConfiguration configuration) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(configuration.getSchema()), "dbo");
    }

    @Override
    protected boolean schemaRequired() {
        return true;
    }

    @Override
    protected int maxIdentifierLength() {
        return 128;
    }

    @Override
    public int nullType() {
        return Types.VARCHAR;
    }

    /**
     * 二进制列的 NULL 须以二进制类型绑定（nvarchar → varbinary 不允许隐式转换）
     */
    @Override
    public int nullType(String columnType) {
        String type = StringUtils.upperCase(StringUtils.substringBefore(StringUtils.trimToEmpty(columnType), "("));
        return Set.of("BINARY", "VARBINARY", "IMAGE", "TIMESTAMP", "ROWVERSION").contains(type) ? Types.VARBINARY : nullType();
    }

    /**
     * SQL Server 的 TINYINT 为无符号（0~255），按无符号类型映射，避免目标端有符号 TINYINT 溢出
     */
    @Override
    protected TableFieldDTO buildField(String name, String typeName, int jdbcType, int size, int scale, int displaySize, String remarks) {
        TableFieldDTO field = super.buildField(name, typeName, jdbcType, size, scale, displaySize, remarks);
        if ("tinyint".equalsIgnoreCase(StringUtils.trimToEmpty(typeName))) {
            field.setFieldSourceStandardType(io.dataease.extensions.sync.datatype.StandardDataType.TINYINT_UNSIGNED.name());
            field.setFieldType(field.getFieldSourceStandardType());
        }
        return field;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return TYPES;
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of("CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "DECIMAL", "NUMERIC", "BINARY", "VARBINARY");
    }

    @Override
    public String columnType(TableFieldDTO field) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(field.getFieldType()));
        if (Set.of("VARCHAR", "NVARCHAR", "VARBINARY").contains(type)
                && (field.getFieldSize() <= 0 || field.getFieldSize() > ("NVARCHAR".equals(type) ? 4000 : 8000))) {
            return type + "(MAX)";
        }
        return super.columnType(field);
    }

    @Override
    public List<String> unsupportedKeyFieldTypes(DatasourceRequest request) {
        return List.of("TEXT", "NTEXT");
    }

    @Override
    public String fieldIndexType(DatasourceRequest request) {
        return "NONCLUSTERED";
    }

    @Override
    public List<String> supportedIndexFieldTypes(DatasourceRequest request) {
        return List.of("BIT", "TINYINT", "SMALLINT", "INT", "BIGINT", "REAL", "FLOAT", "DECIMAL", "NUMERIC", "MONEY", "CHAR",
                "NCHAR", "DATE", "TIME", "DATETIME", "DATETIME2", "DATETIMEOFFSET", "UNIQUEIDENTIFIER");
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return switch (kind) {
            case BOOL -> MappedType.of("BIT");
            case INT8, INT16, YEAR -> MappedType.of("SMALLINT");
            case INT32 -> MappedType.of("INT");
            case INT64 -> MappedType.of("BIGINT");
            case UINT64 -> MappedType.of("DECIMAL", 20, 0);
            case FLOAT -> MappedType.of("REAL");
            case DOUBLE -> MappedType.of("FLOAT");
            case DECIMAL -> decimal("DECIMAL", size, scale, 38, 18);
            case CHAR -> size > 0 && size <= 4000 ? MappedType.of("NCHAR", size) : MappedType.of("NVARCHAR", 0);
            case VARCHAR, BIT -> size > 0 && size <= 4000 ? MappedType.of("NVARCHAR", size) : MappedType.of("NVARCHAR", 0);
            case TEXT, JSON -> MappedType.of("NVARCHAR", 0);
            case DATE -> MappedType.of("DATE");
            case TIME -> MappedType.of("TIME");
            case DATETIME -> MappedType.of("DATETIME2");
            case DATETIME_TZ -> MappedType.of("DATETIMEOFFSET");
            case BINARY, BLOB -> MappedType.of("VARBINARY", 0);
            case UUID -> MappedType.of("UNIQUEIDENTIFIER");
            default -> MappedType.of(UNKNOWN);
        };
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        List<String> parts = new ArrayList<>();
        for (TableFieldDTO field : target.getFieldList()) {
            parts.add(quote(field.getFieldName()) + " " + columnType(field) + (field.isFieldPk() ? " NOT NULL" : " NULL"));
        }
        List<TableFieldDTO> keys = keyFields(target);
        if (!keys.isEmpty()) {
            parts.add("PRIMARY KEY (" + keys.stream().map(f -> quote(f.getFieldName())).collect(Collectors.joining(", ")) + ")");
        }
        String qualified = qualified(configuration, table);
        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE " + qualified + " (\n  " + String.join(",\n  ", parts) + "\n)");
        String suffix = Long.toString(System.nanoTime(), 36);
        int i = 0;
        for (TableFieldDTO field : target.getFieldList()) {
            if (field.isFieldIndex() && !field.isFieldPk()) {
                statements.add("CREATE NONCLUSTERED INDEX " + quote("idx_" + StringUtils.left(table, 60) + "_" + (i++) + "_" + suffix)
                        + " ON " + qualified + " (" + quote(field.getFieldName()) + ")");
            }
        }
        return statements;
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (tableExists(connection, configuration, table)) {
                execute(connection, "DROP TABLE " + qualified(configuration, table));
            }
            String from = metaSchema(configuration) + "." + tempTable;
            execute(connection, "EXEC sp_rename '" + escapeLiteral(from) + "', '" + escapeLiteral(table) + "'");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
