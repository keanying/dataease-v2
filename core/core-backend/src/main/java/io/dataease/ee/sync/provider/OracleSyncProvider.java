package io.dataease.ee.sync.provider;

import io.dataease.datasource.type.Oracle;
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
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Oracle（源端、目标端）
 */
public class OracleSyncProvider extends JdbcSyncProvider {

    private static final List<String> TYPES = List.of("NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "CHAR", "VARCHAR2",
            "NCHAR", "NVARCHAR2", "CLOB", "NCLOB", "DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "RAW", "BLOB");

    public OracleSyncProvider() {
        super("oracle");
    }

    @Override
    protected Configuration coreConfiguration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, Oracle.class);
    }

    @Override
    protected String quotePrefix() {
        return "\"";
    }

    @Override
    protected String quoteSuffix() {
        return "\"";
    }

    @Override
    protected String metaSchema(DatasourceConfiguration configuration) {
        String schema = StringUtils.trimToNull(configuration.getSchema());
        if (schema == null && StringUtils.isNotBlank(configuration.getUsername())) {
            schema = configuration.getUsername().toUpperCase(Locale.ROOT);
        }
        return schema;
    }

    @Override
    protected boolean schemaRequired() {
        return true;
    }

    @Override
    protected int maxIdentifierLength() {
        return 30;
    }

    @Override
    protected String testQuery() {
        return "SELECT 1 FROM DUAL";
    }

    @Override
    public int nullType() {
        return Types.VARCHAR;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return TYPES;
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of("CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2", "NUMBER", "RAW");
    }

    @Override
    protected Set<String> decimalTypes() {
        return Set.of("NUMBER");
    }

    @Override
    public List<String> unsupportedKeyFieldTypes(DatasourceRequest request) {
        return List.of("CLOB", "NCLOB", "BLOB");
    }

    @Override
    public String fieldIndexType(DatasourceRequest request) {
        return "BTREE";
    }

    @Override
    public List<String> supportedIndexFieldTypes(DatasourceRequest request) {
        return List.of("NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "CHAR", "VARCHAR2", "NCHAR", "NVARCHAR2", "DATE",
                "TIMESTAMP", "RAW");
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return switch (kind) {
            case BOOL -> MappedType.of("NUMBER", 1, 0);
            case INT8 -> MappedType.of("NUMBER", 3, 0);
            case INT16, YEAR -> MappedType.of("NUMBER", 5, 0);
            case INT32 -> MappedType.of("NUMBER", 10, 0);
            case INT64 -> MappedType.of("NUMBER", 19, 0);
            case UINT64 -> MappedType.of("NUMBER", 20, 0);
            case FLOAT -> MappedType.of("BINARY_FLOAT");
            case DOUBLE -> MappedType.of("BINARY_DOUBLE");
            case DECIMAL -> decimal("NUMBER", size, scale, 38, 38);
            case CHAR -> size > 0 && size <= 2000 ? MappedType.of("NCHAR", size) : MappedType.of("NCLOB");
            case VARCHAR, BIT, UUID -> size > 0 && size <= 2000 ? MappedType.of("NVARCHAR2", size)
                    : (size <= 0 ? MappedType.of("NVARCHAR2", 255) : MappedType.of("NCLOB"));
            case TEXT, JSON -> MappedType.of("NCLOB");
            case DATE, DATETIME, TIME -> MappedType.of(kind == Kind.DATE ? "DATE" : "TIMESTAMP");
            case DATETIME_TZ -> MappedType.of("TIMESTAMP WITH TIME ZONE");
            case BINARY -> size > 0 && size <= 2000 ? MappedType.of("RAW", size) : MappedType.of("BLOB");
            case BLOB -> MappedType.of("BLOB");
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
        if (StringUtils.isNotBlank(target.getRemarks())) {
            statements.add("COMMENT ON TABLE " + qualified + " IS '" + escapeLiteral(target.getRemarks()) + "'");
        }
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        int i = 0;
        for (TableFieldDTO field : target.getFieldList()) {
            if (StringUtils.isNotBlank(field.getRemarks())) {
                statements.add("COMMENT ON COLUMN " + qualified + "." + quote(field.getFieldName()) + " IS '" + escapeLiteral(field.getRemarks()) + "'");
            }
            if (field.isFieldIndex() && !field.isFieldPk()) {
                statements.add("CREATE INDEX " + quote("IDX_" + StringUtils.left(table, 14) + "_" + (i++) + "_" + suffix)
                        + " ON " + qualified + " (" + quote(field.getFieldName()) + ")");
            }
        }
        return statements;
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException {
        if (tableExists(connection, configuration, table)) {
            execute(connection, "DROP TABLE " + qualified(configuration, table) + " PURGE");
        }
        execute(connection, "ALTER TABLE " + qualified(configuration, tempTable) + " RENAME TO " + quote(table));
    }
}
