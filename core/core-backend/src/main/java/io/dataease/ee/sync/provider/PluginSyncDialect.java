package io.dataease.ee.sync.provider;

import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.extensions.sync.vo.XpackPluginsSyncDatasourceVO;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 插件同步数据源适配：连接取自插件 {@link SyncProvider#getConnection}，建表/替换优先调用插件的 SinkProvider 能力，
 * 读写使用通用 JDBC（引号取插件声明的 prefix/suffix）。
 */
public class PluginSyncDialect implements SyncDialect {

    private final SyncProvider provider;
    private final DatasourceRequest request;
    private final String prefix;
    private final String suffix;

    public PluginSyncDialect(SyncProvider provider, XpackPluginsSyncDatasourceVO plugin, DatasourceRequest request) {
        this.provider = provider;
        this.request = request;
        this.prefix = plugin == null || plugin.getPrefix() == null ? "\"" : plugin.getPrefix();
        this.suffix = plugin == null || plugin.getSuffix() == null ? "\"" : plugin.getSuffix();
    }

    @Override
    public DatasourceConfiguration configuration(String configurationJson) {
        return JsonUtil.parseObject(configurationJson, DatasourceConfiguration.class);
    }

    @Override
    public Connection open(DatasourceConfiguration configuration) {
        try {
            return provider.getConnection(configuration).getConnection();
        } catch (Exception e) {
            DEException.throwException(SyncJdbcSupport.rootMessage(e));
        }
        return null;
    }

    @Override
    public String quote(String identifier) {
        return prefix + identifier.replace(suffix, suffix + suffix) + suffix;
    }

    @Override
    public String qualified(DatasourceConfiguration configuration, String table) {
        return StringUtils.isBlank(configuration.getSchema()) ? quote(table) : quote(configuration.getSchema()) + "." + quote(table);
    }

    @Override
    public String selectSql(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn) {
        String cols = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String from = StringUtils.equals(source.getTableExtract(), "1")
                ? "(" + JdbcSyncProvider.checkQuery(source.getQuery()) + ") " + quote("de_sync_q")
                : qualified(configuration, source.getTables());
        String sql = "SELECT " + cols + " FROM " + from;
        if (StringUtils.isNotBlank(incrementColumn)) {
            sql += " WHERE " + quote(incrementColumn) + " > ?";
        }
        return sql;
    }

    private SinkProvider sink() {
        if (provider instanceof SinkProvider sink) {
            return sink;
        }
        DEException.throwException("sink not supported: " + provider.getClass().getName());
        return null;
    }

    private DatasourceRequest request(String table) {
        DatasourceRequest copy = new DatasourceRequest();
        org.springframework.beans.BeanUtils.copyProperties(request, copy);
        copy.setTable(table);
        return copy;
    }

    @Override
    public boolean tableExists(Connection connection, DatasourceConfiguration configuration, String table) {
        return sink().checkTableExists(request(table));
    }

    @Override
    public void dropTable(Connection connection, DatasourceConfiguration configuration, String table) {
        if (tableExists(connection, configuration, table)) {
            sink().dropTable(request(table), table);
        }
    }

    @Override
    public List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) {
        Target copy = new Target(target);
        copy.setTableName(table);
        return List.of(sink().getCreateTableSql(new Source(), copy));
    }

    @Override
    public void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException {
        if (StringUtils.equals(tempTable, tempTableName(table))) {
            sink().replaceTable(request(table), table);
            return;
        }
        if (tableExists(connection, configuration, table)) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE " + qualified(configuration, table));
            }
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + qualified(configuration, tempTable) + " RENAME TO " + quote(table));
        }
    }

    @Override
    public String tempTableName(String table) {
        return sink().getTempTableNamePrefix() + table;
    }

    @Override
    public String insertSql(DatasourceConfiguration configuration, String table, List<String> columns) {
        return "INSERT INTO " + qualified(configuration, table) + " ("
                + columns.stream().map(this::quote).collect(Collectors.joining(", ")) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) {
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
    public int nullType() {
        return Types.NULL;
    }

    @Override
    public Object readValue(ResultSet resultSet, int index) throws SQLException {
        return SyncProviderDefaults.readValue(resultSet, index);
    }
}
