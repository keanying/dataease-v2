package io.dataease.ee.sync.provider;

import io.dataease.datasource.type.Db2;
import io.dataease.extensions.datasource.vo.Configuration;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DB2（仅源端）
 */
public class Db2SyncProvider extends JdbcSyncProvider {

    public Db2SyncProvider() {
        super("db2");
    }

    @Override
    protected Configuration coreConfiguration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, Db2.class);
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
    protected String testQuery() {
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }

    @Override
    protected boolean sinkSupported() {
        return false;
    }

    @Override
    protected List<String> sinkDataTypes() {
        return List.of();
    }

    @Override
    protected Set<String> sizedTypes() {
        return Set.of();
    }

    @Override
    protected MappedType mapKind(Kind kind, int size, int scale) {
        return MappedType.of(UNKNOWN);
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        return List.of();
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) {
        throw new UnsupportedOperationException("db2 sink");
    }
}
