package io.dataease.ee.datafilling.jdbc;

import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datasource.dto.ConnectionObj;
import io.dataease.extensions.datasource.dto.TableField;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * 一次对填报存储数据源的 JDBC 会话
 */
public class DfSession implements AutoCloseable {

    private final ConnectionObj connectionObj;

    private final Connection connection;

    private final DfDialect dialect;

    private final String type;

    private ExtDDLProvider ddl;

    /**
     * 显式指定的模式（SQL Server 用于限定表名；为空时使用连接默认模式）
     */
    private String schema;

    public DfSession(ConnectionObj connectionObj, String type) {
        this.connectionObj = connectionObj;
        this.connection = connectionObj.getConnection();
        if (this.connection == null) {
            DEException.throwException("Failed to get connection!");
        }
        this.dialect = DfDialect.of(connection);
        this.type = type;
    }

    public Connection connection() {
        return connection;
    }

    public DfDialect dialect() {
        return dialect;
    }

    public String type() {
        return type;
    }

    public ExtDDLProvider ddl() {
        return ddl;
    }

    public void setDdl(ExtDDLProvider ddl) {
        this.ddl = ddl;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = StringUtils.trimToNull(schema);
    }

    public String q(String name) {
        return dialect.quote(name);
    }

    /**
     * 引用后的表名；SQL Server 指定了模式时带模式前缀
     */
    public String table(String name) {
        if (schema != null && dialect.isSqlServer()) {
            return q(schema) + "." + q(name);
        }
        return q(name);
    }

    /**
     * 执行 DDL，多条语句以分号分隔（引号内的分号不作为分隔符）
     */
    public void execute(String sql) {
        if (StringUtils.isBlank(sql)) {
            return;
        }
        for (String one : split(sql)) {
            executeOne(one);
        }
    }

    /**
     * 执行单条语句（不拆分）
     */
    public void executeOne(String sql) {
        if (StringUtils.isBlank(sql)) {
            return;
        }
        LogUtil.info("data filling ddl: " + sql);
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
    }

    /**
     * 按分号拆分语句，跳过单引号、双引号、反引号、方括号内的内容
     */
    static List<String> split(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                current.append(c);
                char close = quote == '[' ? ']' : quote;
                if (c == close) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                        current.append(sql.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                quote = c;
                current.append(c);
            } else if (c == ';') {
                if (StringUtils.isNotBlank(current)) {
                    result.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (StringUtils.isNotBlank(current)) {
            result.add(current.toString().trim());
        }
        return result;
    }

    public int update(String sql, List<Object> params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 插入并返回自增主键（不支持时返回 null）
     */
    public List<String> insert(String sql, List<Object> params, String pk) {
        List<String> keys = new ArrayList<>();
        if (pk != null && dialect.isOracle()) {
            // ojdbc 的 prepareStatement(sql, String[]) 会把带引号的小写表名转成大写去查找，改用 RETURNING INTO
            try (CallableStatement cs = connection.prepareCall("BEGIN " + sql + " RETURNING " + q(pk) + " INTO ?; END;")) {
                bind(cs, params);
                int out = (params == null ? 0 : params.size()) + 1;
                cs.registerOutParameter(out, Types.VARCHAR);
                cs.execute();
                String key = cs.getString(out);
                if (key != null) {
                    keys.add(key);
                }
            } catch (SQLException e) {
                DEException.throwException("SQL ERROR: " + e.getMessage());
            }
            return keys;
        }
        try (PreparedStatement ps = pk == null ? connection.prepareStatement(sql)
                : connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                while (rs != null && rs.next()) {
                    Object v = rs.getObject(1);
                    if (v != null) {
                        keys.add(v.toString());
                    }
                }
            } catch (Exception ignore) {
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return keys;
    }

    public List<Map<String, Object>> query(String sql, List<Object> params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= count; i++) {
                        String label = StringUtils.defaultIfBlank(meta.getColumnLabel(i), meta.getColumnName(i));
                        row.put(label, readValue(rs, i, meta.getColumnType(i)));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return rows;
    }

    /**
     * 查询并以字符串形式返回（日期格式化为 yyyy-MM-dd HH:mm:ss）
     */
    public List<List<String>> queryStrings(String sql, List<Object> params, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (maxRows > 0) {
                ps.setMaxRows(maxRows);
            }
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        int t = meta.getColumnType(i);
                        if (t == Types.TIMESTAMP || t == Types.DATE || t == Types.TIMESTAMP_WITH_TIMEZONE) {
                            Timestamp ts = rs.getTimestamp(i);
                            row.add(ts == null ? null : sdf.format(ts));
                        } else {
                            Object o = rs.getObject(i);
                            if (o instanceof BigDecimal d) {
                                row.add(d.stripTrailingZeros().toPlainString());
                            } else {
                                row.add(o == null ? null : rs.getString(i));
                            }
                        }
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return rows;
    }

    public long count(String sql, List<Object> params) {
        List<Map<String, Object>> rows = query(sql, params);
        if (rows.isEmpty()) {
            return 0;
        }
        Object v = rows.getFirst().values().iterator().next();
        return v == null ? 0 : new BigDecimal(v.toString()).longValue();
    }

    private Object readValue(ResultSet rs, int i, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp ts = rs.getTimestamp(i);
                return ts == null ? null : ts.getTime();
            }
            case Types.DATE -> {
                java.sql.Date d = rs.getDate(i);
                return d == null ? null : d.getTime();
            }
            case Types.TIME -> {
                Time t = rs.getTime(i);
                return t == null ? null : t.toString();
            }
            case Types.CLOB, Types.NCLOB -> {
                Clob clob = rs.getClob(i);
                if (clob == null) {
                    return null;
                }
                try (Reader reader = clob.getCharacterStream()) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[2048];
                    int n;
                    while ((n = reader.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                    }
                    return sb.toString();
                } catch (Exception e) {
                    return null;
                }
            }
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                return null;
            }
            case Types.BIT, Types.BOOLEAN -> {
                Object o = rs.getObject(i);
                return o;
            }
            default -> {
                Object o = rs.getObject(i);
                if (o instanceof java.time.LocalDateTime ldt) {
                    return Timestamp.valueOf(ldt).getTime();
                }
                if (o instanceof java.time.LocalDate ld) {
                    return java.sql.Date.valueOf(ld).getTime();
                }
                if (o instanceof java.math.BigInteger bi) {
                    return bi.toString();
                }
                return o;
            }
        }
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null) {
                ps.setNull(i + 1, dialect.nullType());
            } else if (v instanceof java.util.Date date && !(v instanceof Timestamp) && !(v instanceof java.sql.Date)) {
                ps.setTimestamp(i + 1, new Timestamp(date.getTime()));
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    /**
     * 列出当前库/模式下的表
     */
    public List<String> tables() {
        List<String> result = new ArrayList<>();
        try {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(connection.getCatalog(), schema(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    result.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return result;
    }

    public boolean tableExists(String table) {
        return tables().stream().anyMatch(t -> StringUtils.equalsIgnoreCase(t, table));
    }

    /**
     * 表字段（含主键标识）
     */
    public List<TableField> columns(String table) {
        List<TableField> result = new ArrayList<>();
        String realTable = tables().stream().filter(t -> StringUtils.equalsIgnoreCase(t, table)).findFirst().orElse(table);
        try {
            DatabaseMetaData meta = connection.getMetaData();
            Set<String> pks = new HashSet<>();
            try (ResultSet rs = meta.getPrimaryKeys(connection.getCatalog(), schema(), realTable)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), schema(), realTable, "%")) {
                while (rs.next()) {
                    TableField field = new TableField();
                    String name = rs.getString("COLUMN_NAME");
                    String typeName = rs.getString("TYPE_NAME");
                    int dataType = rs.getInt("DATA_TYPE");
                    field.setOriginName(name);
                    field.setName(StringUtils.defaultIfBlank(rs.getString("REMARKS"), name));
                    field.setType(typeName);
                    field.setFieldType(typeName);
                    field.setSize(rs.getLong("COLUMN_SIZE"));
                    field.setDeType(deType(dataType));
                    field.setDeExtractType(field.getDeType());
                    field.setPrimary(pks.contains(name));
                    field.setPrimaryKey(pks.contains(name));
                    result.add(field);
                }
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return result;
    }

    /**
     * 列名到 JDBC 类型
     */
    public Map<String, Integer> columnTypes(String table) {
        Map<String, Integer> result = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table(table) + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                result.put(meta.getColumnName(i).toLowerCase(), meta.getColumnType(i));
            }
        } catch (SQLException e) {
            DEException.throwException("SQL ERROR: " + e.getMessage());
        }
        return result;
    }

    private String schema() {
        if (schema != null) {
            return schema;
        }
        try {
            String current = connection.getSchema();
            return StringUtils.isBlank(current) ? null : current;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 表中数据行数
     */
    public long rowCount(String table) {
        return count("SELECT COUNT(1) FROM " + table(table), null);
    }

    private static int deType(int sqlType) {
        return switch (sqlType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> 1;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> 2;
            case Types.FLOAT, Types.DOUBLE, Types.REAL, Types.DECIMAL, Types.NUMERIC -> 3;
            case Types.BIT, Types.BOOLEAN -> 4;
            default -> 0;
        };
    }

    @Override
    public void close() {
        try {
            connectionObj.close();
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
    }
}
