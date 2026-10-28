package io.dataease.ee.datasource.dm;

import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.dto.*;
import io.dataease.extensions.datasource.factory.ProviderFactory;
import io.dataease.extensions.datasource.plugin.DataEaseDatasourcePlugin;
import io.dataease.extensions.datasource.provider.ExtendedJdbcClassLoader;
import io.dataease.extensions.datasource.vo.XpackPluginsDatasourceVO;
import io.dataease.i18n.Translator;
import io.dataease.plugins.vo.DataEasePluginVO;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.*;

/**
 * 内置的达梦（DM8）数据源，类型 {@code dm}。
 *
 * <p>社区版核心只把 dm 当作插件类型（见 {@code DataSourceManage.getFlag}），本类以“内置插件”的方式注册到
 * {@link ProviderFactory}（不需要安装插件包），驱动使用 {@code dataease.path.driver} 目录中的 DmJdbcDriver18.jar：</p>
 * <ul>
 *     <li>连接：{@link DmConfiguration}，连接后按配置的模式执行 {@code SET SCHEMA}；</li>
 *     <li>模式、表（含视图）与字段通过 ALL_* 数据字典视图读取（{@link DmDictionary}，驱动元数据需要系统表权限）；</li>
 *     <li>查询由核心按 Oracle 方言（Calcite {@link OracleSqlDialect}，OFFSET/FETCH 分页）生成 SQL 后直接在达梦执行；
 *     达梦与其他数据源之间不支持跨源关联（核心 {@code DatasetSQLManage.invalidCrossDs} 已排除 dm）；</li>
 *     <li>每次请求新建连接（不使用连接池）。</li>
 * </ul>
 * <p>若安装了第三方达梦数据源插件包，以先注册者为准。</p>
 */
@Component
public class DmDatasourceProvider extends DataEaseDatasourcePlugin {

    public static final String TYPE = "dm";

    public static final String MODULE_NAME = "builtin-datasource-dm";

    private static final int QUERY_TIMEOUT = 30;

    @Value("${dataease.path.driver:/opt/dataease3.0/drivers}")
    private String driverPath;

    private volatile ExtendedJdbcClassLoader classLoader;

    @EventListener(ApplicationReadyEvent.class)
    @Order
    public void register() {
        try {
            if (ProviderFactory.getInstance(TYPE) != null) {
                LogUtil.info("dm datasource provider already registered by a plugin, skip the built-in one");
                return;
            }
            ProviderFactory.loadPlugin(TYPE, this);
            LogUtil.info("built-in dm datasource provider registered");
        } catch (Exception e) {
            LogUtil.warn("register built-in dm datasource provider failed: " + e.getMessage());
        }
    }

    /**
     * 是否为本类注册的内置实现
     */
    public static boolean builtinActive() {
        try {
            return ProviderFactory.getInstance(TYPE) instanceof DmDatasourceProvider;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 插件描述（内置，无插件包）

    @Override
    public DataEasePluginVO getPluginInfo() {
        return getPluginMetadata();
    }

    @Override
    public DataEasePluginVO getPluginMetadata() {
        DataEasePluginVO vo = new DataEasePluginVO();
        vo.setModuleName(MODULE_NAME);
        vo.setName("DM");
        vo.setConfig(JsonUtil.toJSONString(getConfig()).toString());
        return vo;
    }

    @Override
    public XpackPluginsDatasourceVO getConfig() {
        XpackPluginsDatasourceVO vo = new XpackPluginsDatasourceVO();
        vo.setType(TYPE);
        vo.setName("DM");
        vo.setCategory("OLTP");
        vo.setFlag(23);
        vo.setPrefix("\"");
        vo.setSuffix("\"");
        vo.setExtraParams("");
        return vo;
    }

    @Override
    public void loadPlugin() {
        register();
    }

    @Override
    public void unloadPlugin() {
    }

    // ------------------------------------------------------------------ 连接

    private ExtendedJdbcClassLoader classLoader() throws Exception {
        if (classLoader == null) {
            synchronized (this) {
                if (classLoader == null) {
                    ExtendedJdbcClassLoader loader = new ExtendedJdbcClassLoader(new URL[0], getClass().getClassLoader());
                    File[] files = new File(driverPath).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile() && StringUtils.startsWithIgnoreCase(file.getName(), "Dm") && file.getName().endsWith(".jar")) {
                                loader.addFile(file);
                            }
                        }
                    }
                    classLoader = loader;
                }
            }
        }
        return classLoader;
    }

    public static DmConfiguration configuration(DatasourceDTO datasource) {
        DmConfiguration configuration = JsonUtil.parseObject(datasource.getConfiguration(), DmConfiguration.class);
        if (configuration == null) {
            DEException.throwException("Invalid DM configuration");
        }
        return configuration;
    }

    @Override
    public ConnectionObj getConnection(DatasourceDTO datasource) throws Exception {
        DmConfiguration configuration = configuration(datasource);
        ConnectionObj connectionObj = new ConnectionObj();
        startSshSession(configuration, connectionObj, null);
        Properties props = new Properties();
        if (StringUtils.isNotBlank(configuration.getUsername())) {
            props.setProperty("user", configuration.getUsername());
        }
        if (StringUtils.isNotBlank(configuration.getPassword())) {
            props.setProperty("password", configuration.getPassword());
        }
        Driver driver;
        try {
            driver = (Driver) classLoader().loadClass(StringUtils.defaultIfBlank(configuration.getDriver(), DmConfiguration.DRIVER))
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            DEException.throwException(Translator.get("ee_dm_driver_missing", driverPath));
            return null;
        }
        Connection connection;
        try {
            connection = driver.connect(configuration.getJdbc(), props);
        } catch (SQLException e) {
            connectionObj.close();
            throw e;
        }
        if (connection == null) {
            connectionObj.close();
            DEException.throwException("Failed to get connection!");
        }
        connectionObj.setConnection(connection);
        if (StringUtils.isNotBlank(configuration.getSchema())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SCHEMA " + quote(configuration.getSchema().trim()));
            } catch (SQLException e) {
                connectionObj.close();
                DEException.throwException(Translator.get("ee_dm_schema_invalid", configuration.getSchema()) + ": " + e.getMessage());
            }
        }
        return connectionObj;
    }

    private static String quote(String name) {
        return "\"" + StringUtils.replace(name, "\"", "\"\"") + "\"";
    }

    private static String schemaOf(DatasourceDTO datasource, Connection connection) {
        DmConfiguration configuration = configuration(datasource);
        if (StringUtils.isNotBlank(configuration.getSchema())) {
            return configuration.getSchema().trim();
        }
        try {
            return connection.getSchema();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String checkStatus(DatasourceRequest datasourceRequest) throws Exception {
        try (ConnectionObj obj = getConnection(datasourceRequest.getDatasource());
             Statement statement = obj.getConnection().createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT);
            try (ResultSet ignored = statement.executeQuery("SELECT 1 FROM DUAL")) {
                datasourceRequest.setDsVersion(obj.getConnection().getMetaData().getDatabaseMajorVersion());
            }
        }
        return "Success";
    }

    @Override
    public List<String> getSchema(DatasourceRequest datasourceRequest) {
        List<String> schemas = new ArrayList<>();
        try (ConnectionObj obj = connectionWithoutSchema(datasourceRequest.getDatasource())) {
            schemas.addAll(DmDictionary.schemas(obj.getConnection()));
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
        return schemas;
    }

    /**
     * 读取模式列表时不切换模式（配置的模式可能尚未选择或已失效）
     */
    private ConnectionObj connectionWithoutSchema(DatasourceDTO datasource) throws Exception {
        DmConfiguration configuration = configuration(datasource);
        configuration.setSchema(null);
        DatasourceDTO copy = new DatasourceDTO();
        copy.setId(datasource.getId());
        copy.setType(datasource.getType());
        copy.setConfiguration(JsonUtil.toJSONString(configuration).toString());
        return getConnection(copy);
    }

    @Override
    public List<DatasetTableDTO> getTables(DatasourceRequest datasourceRequest) {
        List<DatasetTableDTO> tables = new ArrayList<>();
        DatasourceDTO datasource = datasourceRequest.getDatasource();
        try (ConnectionObj obj = getConnection(datasource)) {
            Connection connection = obj.getConnection();
            String schema = schemaOf(datasource, connection);
            for (DmDictionary.Table t : DmDictionary.tables(connection, schema, true)) {
                DatasetTableDTO table = new DatasetTableDTO();
                table.setDatasourceId(datasource.getId());
                table.setType("db");
                table.setTableName(t.name());
                table.setName(StringUtils.defaultIfBlank(t.remarks(), t.name()));
                tables.add(table);
            }
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
        return tables;
    }

    @Override
    public List<TableField> fetchTableField(DatasourceRequest datasourceRequest) throws DEException {
        DatasourceDTO datasource = datasource(datasourceRequest);
        List<TableField> fields = new ArrayList<>();
        try (ConnectionObj obj = getConnection(datasource)) {
            Connection connection = obj.getConnection();
            if (StringUtils.isBlank(datasourceRequest.getTable())) {
                try (PreparedStatement ps = prepare(connection, datasourceRequest);
                     ResultSet rs = ps.executeQuery()) {
                    return resultFields(rs);
                }
            }
            String schema = schemaOf(datasource, connection);
            String table = datasourceRequest.getTable();
            Set<String> pks = DmDictionary.primaryKeys(connection, schema, table);
            for (DmDictionary.Column c : DmDictionary.columns(connection, schema, table)) {
                TableField field = new TableField();
                String name = c.name();
                field.setOriginName(name);
                field.setName(StringUtils.defaultIfBlank(c.remarks(), name));
                field.setType(c.typeName());
                field.setFieldType(c.typeName());
                field.setSize(c.size());
                if (c.scale() != null) {
                    field.setScale(c.scale());
                }
                int deType = deType(c.typeName());
                field.setDeType(deType);
                field.setDeExtractType(deType);
                field.setPrimary(pks.contains(name));
                field.setPrimaryKey(pks.contains(name));
                field.setTypeNumber(DmDictionary.jdbcType(c.typeName()));
                fields.add(field);
            }
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
        return fields;
    }

    @Override
    public Map<String, Object> fetchResultField(DatasourceRequest datasourceRequest) throws DEException {
        DatasourceDTO datasource = datasource(datasourceRequest);
        Map<String, Object> result = new LinkedHashMap<>();
        try (ConnectionObj obj = getConnection(datasource);
             PreparedStatement ps = prepare(obj.getConnection(), datasourceRequest);
             ResultSet rs = ps.executeQuery()) {
            result.put("fields", resultFields(rs));
            result.put("data", rows(rs));
        } catch (DEException e) {
            throw e;
        } catch (SQLException e) {
            LogUtil.error("dm query failed: " + datasourceRequest.getQuery(), e);
            DEException.throwException("SQL ERROR: " + e.getMessage());
        } catch (Exception e) {
            DEException.throwException("Datasource connection exception: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void exec(DatasourceRequest datasourceRequest) {
        DatasourceDTO datasource = datasource(datasourceRequest);
        try (ConnectionObj obj = getConnection(datasource);
             PreparedStatement ps = prepare(obj.getConnection(), datasourceRequest)) {
            ps.execute();
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
    }

    @Override
    public boolean supportsWindowFunctions(DatasourceRequest datasourceRequest) {
        return true;
    }

    /**
     * 达梦语法与 Oracle 兼容，按 Oracle 12c 方言（OFFSET ... FETCH NEXT 分页）生成 SQL
     */
    @Override
    public SqlDialect getDialect(DatasourceSchemaDTO coreDatasource) {
        return new OracleSqlDialect(OracleSqlDialect.DEFAULT_CONTEXT, 12);
    }

    @Override
    public void hidePW(DatasourceDTO datasourceDTO) {
        DmConfiguration configuration = configuration(datasourceDTO);
        if (StringUtils.equalsIgnoreCase(configuration.getUrlType(), "jdbcUrl") && StringUtils.contains(configuration.getJdbcUrl(), "password=")) {
            configuration.setJdbcUrl(configuration.getJdbcUrl().replaceAll("password=[^&;]*", "password=******"));
            datasourceDTO.setConfiguration(JsonUtil.toJSONString(configuration).toString());
        }
    }

    // ------------------------------------------------------------------ 工具

    private static DatasourceDTO datasource(DatasourceRequest request) {
        if (request.getDsList() != null && !request.getDsList().isEmpty()) {
            DatasourceSchemaDTO value = request.getDsList().values().iterator().next();
            request.setDatasource(value);
            return value;
        }
        return request.getDatasource();
    }

    private static PreparedStatement prepare(Connection connection, DatasourceRequest request) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(request.getQuery());
        ps.setQueryTimeout(QUERY_TIMEOUT);
        List<TableFieldWithValue> values = request.getTableFieldWithValues();
        if (CollectionUtils.isNotEmpty(values)) {
            for (int i = 0; i < values.size(); i++) {
                TableFieldWithValue v = values.get(i);
                Object value = v.getValue();
                if (value == null) {
                    ps.setNull(i + 1, v.getType() == null ? Types.VARCHAR : v.getType());
                } else if (v.getType() != null && v.getType() == Types.CLOB && value instanceof String s) {
                    ps.setCharacterStream(i + 1, new java.io.StringReader(s), s.length());
                } else if (v.getType() != null) {
                    ps.setObject(i + 1, value, v.getType());
                } else {
                    ps.setObject(i + 1, value);
                }
            }
        }
        return ps;
    }

    private static List<TableField> resultFields(ResultSet rs) throws SQLException {
        List<TableField> fields = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = StringUtils.defaultIfBlank(meta.getColumnLabel(i), meta.getColumnName(i));
            if (StringUtils.equalsIgnoreCase(label, "ROWNUM") || StringUtils.equalsIgnoreCase(label, "DE_ROWNUM")) {
                continue;
            }
            String type = StringUtils.upperCase(meta.getColumnTypeName(i));
            TableField field = new TableField();
            field.setOriginName(label);
            field.setName(label);
            field.setType(type);
            field.setFieldType(type);
            field.setPrecision(meta.getPrecision(i));
            field.setScale(meta.getScale(i));
            int deType = deType(type);
            field.setDeType(deType);
            field.setDeExtractType(deType);
            fields.add(field);
        }
        return fields;
    }

    private static List<String[]> rows(ResultSet rs) throws Exception {
        List<String[]> rows = new LinkedList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        while (rs.next()) {
            String[] row = new String[count];
            for (int j = 1; j <= count; j++) {
                row[j - 1] = value(rs, j, meta.getColumnType(j));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String value(ResultSet rs, int index, int sqlType) throws Exception {
        switch (sqlType) {
            case Types.DATE -> {
                java.sql.Date d = rs.getDate(index);
                return d == null ? null : d.toString();
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp ts = rs.getTimestamp(index);
                return ts == null ? null : ts.toString();
            }
            case Types.BIT, Types.BOOLEAN -> {
                boolean b = rs.getBoolean(index);
                return rs.wasNull() ? null : (b ? "1" : "0");
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal d = rs.getBigDecimal(index);
                return d == null ? null : d.toString();
            }
            case Types.CLOB, Types.NCLOB -> {
                Clob clob = rs.getClob(index);
                if (clob == null) {
                    return null;
                }
                try (Reader reader = clob.getCharacterStream()) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                    }
                    return sb.toString();
                }
            }
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                byte[] bytes = rs.getBytes(index);
                return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
            }
            default -> {
                return rs.getString(index);
            }
        }
    }

    /**
     * 达梦字段类型 → DataEase 字段类型（0 文本、1 时间、2 整数、3 小数、4 布尔、5 地理，二进制按文本）
     */
    public static int deType(String typeName) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(typeName));
        int paren = type.indexOf('(');
        if (paren > 0) {
            type = type.substring(0, paren).trim();
        }
        return switch (type) {
            case "DATE", "TIME", "TIMESTAMP", "DATETIME", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE",
                 "TIMESTAMP WITH LOCAL TIME ZONE", "DATETIME WITH TIME ZONE" -> 1;
            case "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "BYTE", "PLS_INTEGER" -> 2;
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL" -> 3;
            case "BIT", "BOOLEAN", "BOOL" -> 4;
            default -> 0;
        };
    }
}
