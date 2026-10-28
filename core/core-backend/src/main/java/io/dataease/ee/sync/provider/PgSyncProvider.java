package io.dataease.ee.sync.provider;

import io.dataease.datasource.type.Pg;
import io.dataease.extensions.datasource.vo.Configuration;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL（源端、目标端）
 */
public class PgSyncProvider extends JdbcSyncProvider {

    private static final List<String> TYPES = List.of("SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE PRECISION", "NUMERIC",
            "BOOLEAN", "CHAR", "VARCHAR", "TEXT", "JSON", "JSONB", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "BYTEA", "UUID");

    public PgSyncProvider() {
        super("postgresql");
    }

    @Override
    protected Configuration coreConfiguration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, Pg.class);
    }

    /**
     * 文本参数按 unspecified 发送，由服务端推断类型：写入 JSON/JSONB、UUID 等列时无需显式转换
     */
    @Override
    public Connection open(DatasourceConfiguration configuration) {
        String url = configuration.getJdbc();
        if (url != null && !url.toLowerCase(java.util.Locale.ROOT).contains("stringtype=")) {
            url += (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
        }
        return SyncJdbcSupport.connect(configuration.getDriver(), url, configuration.getUsername(), configuration.getPassword());
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
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(configuration.getSchema()), "public");
    }

    @Override
    protected int maxIdentifierLength() {
        return 63;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return TYPES;
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of("CHAR", "VARCHAR", "NUMERIC");
    }

    @Override
    public List<String> unsupportedKeyFieldTypes(DatasourceRequest request) {
        return List.of("JSON", "BYTEA");
    }

    @Override
    public String fieldIndexType(DatasourceRequest request) {
        return "BTREE";
    }

    @Override
    public List<String> supportedIndexFieldTypes(DatasourceRequest request) {
        return List.of("SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE PRECISION", "NUMERIC", "BOOLEAN", "CHAR", "VARCHAR",
                "TEXT", "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "UUID");
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return switch (kind) {
            case BOOL -> MappedType.of("BOOLEAN");
            case INT8, INT16, YEAR -> MappedType.of("SMALLINT");
            case INT32 -> MappedType.of("INTEGER");
            case INT64 -> MappedType.of("BIGINT");
            case UINT64 -> MappedType.of("NUMERIC", 20, 0);
            case FLOAT -> MappedType.of("REAL");
            case DOUBLE -> MappedType.of("DOUBLE PRECISION");
            case DECIMAL -> decimal("NUMERIC", size, scale, 1000, 1000);
            case CHAR -> size > 0 ? MappedType.of("CHAR", size) : MappedType.of("VARCHAR", 255);
            case VARCHAR -> size > 0 && size <= 10485760 ? MappedType.of("VARCHAR", size) : MappedType.of("TEXT");
            case TEXT, BIT -> MappedType.of("TEXT");
            case JSON -> MappedType.of("JSONB");
            case DATE -> MappedType.of("DATE");
            case TIME -> MappedType.of("TIME");
            case DATETIME -> MappedType.of("TIMESTAMP");
            case DATETIME_TZ -> MappedType.of("TIMESTAMPTZ");
            case BINARY, BLOB -> MappedType.of("BYTEA");
            case UUID -> MappedType.of("UUID");
            default -> MappedType.of(UNKNOWN);
        };
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        List<String> parts = new ArrayList<>();
        for (TableFieldDTO field : target.getFieldList()) {
            parts.add(quote(field.getFieldName()) + " " + columnType(field) + (field.isFieldPk() ? " NOT NULL" : " NULL"));
        }
        String suffix = Long.toString(System.nanoTime(), 36);
        List<TableFieldDTO> keys = keyFields(target);
        if (!keys.isEmpty()) {
            parts.add("CONSTRAINT " + quote(StringUtils.left("pk_" + table, 50) + "_" + suffix) + " PRIMARY KEY ("
                    + keys.stream().map(f -> quote(f.getFieldName())).collect(Collectors.joining(", ")) + ")");
        }
        String qualified = qualified(configuration, table);
        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE " + qualified + " (\n  " + String.join(",\n  ", parts) + "\n)");
        if (StringUtils.isNotBlank(target.getRemarks())) {
            statements.add("COMMENT ON TABLE " + qualified + " IS '" + escapeLiteral(target.getRemarks()) + "'");
        }
        int i = 0;
        for (TableFieldDTO field : target.getFieldList()) {
            if (StringUtils.isNotBlank(field.getRemarks())) {
                statements.add("COMMENT ON COLUMN " + qualified + "." + quote(field.getFieldName()) + " IS '" + escapeLiteral(field.getRemarks()) + "'");
            }
            if (field.isFieldIndex() && !field.isFieldPk()) {
                statements.add("CREATE INDEX " + quote(StringUtils.left("idx_" + table, 40) + "_" + (i++) + "_" + suffix)
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
            execute(connection, "ALTER TABLE " + qualified(configuration, tempTable) + " RENAME TO " + quote(table));
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) {
        String conflict = keys.stream().map(this::quote).collect(Collectors.joining(", "));
        List<String> updates = columns.stream().filter(c -> !keys.contains(c))
                .map(c -> quote(c) + " = EXCLUDED." + quote(c)).toList();
        return insertSql(configuration, table, columns) + " ON CONFLICT (" + conflict + ") "
                + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + String.join(", ", updates));
    }

    @Override
    protected void checkRequired(DatasourceConfiguration cfg) {
        super.checkRequired(cfg);
        if (cfg.getSchema() != null && cfg.getSchema().contains("\"")) {
            io.dataease.exception.DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
    }
}
