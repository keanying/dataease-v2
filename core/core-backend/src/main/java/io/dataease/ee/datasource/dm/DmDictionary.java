package io.dataease.ee.datasource.dm;

import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 通过达梦的 Oracle 兼容数据字典视图（ALL_TABLES、ALL_TAB_COLUMNS 等）读取模式、表与字段。
 *
 * <p>达梦 JDBC 驱动的 {@code DatabaseMetaData.getTables/getColumns/getPrimaryKeys/getSchemas} 直接查询
 * SYS.SYSOBJECTS、SYSINDEXES 等系统表，普通用户（仅 PUBLIC、RESOURCE 角色）没有查询权限，
 * 而 ALL_* 视图对所有用户可用，只返回其有权访问的对象。</p>
 */
public final class DmDictionary {

    private DmDictionary() {
    }

    public record Table(String name, String remarks, boolean view) {
    }

    public record Column(String name, String typeName, long size, Integer precision, Integer scale, boolean nullable,
                         String remarks) {
    }

    /**
     * 当前用户可见的模式（有对象的用户及当前用户）
     */
    public static List<String> schemas(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    /**
     * 模式下的表（views 为 true 时含视图）
     */
    public static List<Table> tables(Connection connection, String schema, boolean views) throws SQLException {
        String owner = owner(connection, schema);
        List<Table> result = new ArrayList<>();
        String sql = "SELECT T.TABLE_NAME, C.COMMENTS, 0 FROM ALL_TABLES T LEFT JOIN ALL_TAB_COMMENTS C"
                + " ON C.OWNER = T.OWNER AND C.TABLE_NAME = T.TABLE_NAME WHERE T.OWNER = ?";
        if (views) {
            sql += " UNION ALL SELECT V.VIEW_NAME, C.COMMENTS, 1 FROM ALL_VIEWS V LEFT JOIN ALL_TAB_COMMENTS C"
                    + " ON C.OWNER = V.OWNER AND C.TABLE_NAME = V.VIEW_NAME WHERE V.OWNER = ?";
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            if (views) {
                ps.setString(2, owner);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Table(rs.getString(1), rs.getString(2), rs.getInt(3) == 1));
                }
            }
        }
        result.sort(Comparator.comparing(Table::name));
        return result;
    }

    /**
     * 表/视图的字段（按定义顺序）；table 按名称精确匹配，找不到时再忽略大小写匹配
     */
    public static List<Column> columns(Connection connection, String schema, String table) throws SQLException {
        String owner = owner(connection, schema);
        String real = realTable(connection, owner, table);
        List<Column> result = new ArrayList<>();
        String sql = "SELECT A.COLUMN_NAME, A.DATA_TYPE, A.DATA_LENGTH, A.DATA_PRECISION, A.DATA_SCALE, A.NULLABLE, C.COMMENTS"
                + " FROM ALL_TAB_COLUMNS A LEFT JOIN ALL_COL_COMMENTS C ON C.OWNER = A.OWNER AND C.TABLE_NAME = A.TABLE_NAME"
                + " AND C.COLUMN_NAME = A.COLUMN_NAME WHERE A.OWNER = ? AND A.TABLE_NAME = ? ORDER BY A.COLUMN_ID";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, real);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long length = rs.getLong(3);
                    Integer precision = intOrNull(rs, 4);
                    Integer scale = intOrNull(rs, 5);
                    long size = precision != null && precision > 0 ? precision : length;
                    result.add(new Column(rs.getString(1), StringUtils.upperCase(rs.getString(2)), size, precision, scale,
                            !"N".equalsIgnoreCase(rs.getString(6)), rs.getString(7)));
                }
            }
        }
        return result;
    }

    /**
     * 主键列名
     */
    public static Set<String> primaryKeys(Connection connection, String schema, String table) throws SQLException {
        String owner = owner(connection, schema);
        String real = realTable(connection, owner, table);
        Set<String> result = new LinkedHashSet<>();
        String sql = "SELECT CC.COLUMN_NAME FROM ALL_CONSTRAINTS C JOIN ALL_CONS_COLUMNS CC ON CC.OWNER = C.OWNER"
                + " AND CC.CONSTRAINT_NAME = C.CONSTRAINT_NAME WHERE C.OWNER = ? AND C.TABLE_NAME = ? AND C.CONSTRAINT_TYPE = 'P'"
                + " ORDER BY CC.POSITION";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, real);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    private static String realTable(Connection connection, String owner, String table) throws SQLException {
        String sql = "SELECT TABLE_NAME FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND UPPER(TABLE_NAME) = UPPER(?) GROUP BY TABLE_NAME";
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        if (names.contains(table) || names.isEmpty()) {
            return table;
        }
        return names.getFirst();
    }

    /**
     * 模式名：未指定时取当前模式
     */
    private static String owner(Connection connection, String schema) throws SQLException {
        if (StringUtils.isNotBlank(schema)) {
            return schema.trim();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * 达梦字段类型名 → {@link java.sql.Types}
     */
    public static int jdbcType(String typeName) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(typeName));
        int paren = type.indexOf('(');
        if (paren > 0) {
            type = type.substring(0, paren).trim();
        }
        return switch (type) {
            case "CHAR", "CHARACTER" -> java.sql.Types.CHAR;
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "ROWID" -> java.sql.Types.VARCHAR;
            case "TEXT", "LONG", "LONGVARCHAR", "CLOB" -> java.sql.Types.CLOB;
            case "NCLOB" -> java.sql.Types.NCLOB;
            case "BLOB", "IMAGE", "LONGVARBINARY" -> java.sql.Types.BLOB;
            case "BINARY" -> java.sql.Types.BINARY;
            case "VARBINARY", "RAW" -> java.sql.Types.VARBINARY;
            case "BIT" -> java.sql.Types.BIT;
            case "BOOLEAN", "BOOL" -> java.sql.Types.BOOLEAN;
            case "TINYINT", "BYTE" -> java.sql.Types.TINYINT;
            case "SMALLINT" -> java.sql.Types.SMALLINT;
            case "INT", "INTEGER", "PLS_INTEGER" -> java.sql.Types.INTEGER;
            case "BIGINT" -> java.sql.Types.BIGINT;
            case "REAL" -> java.sql.Types.REAL;
            case "FLOAT" -> java.sql.Types.FLOAT;
            case "DOUBLE", "DOUBLE PRECISION" -> java.sql.Types.DOUBLE;
            case "NUMBER", "NUMERIC" -> java.sql.Types.NUMERIC;
            case "DECIMAL", "DEC" -> java.sql.Types.DECIMAL;
            case "DATE" -> java.sql.Types.DATE;
            case "TIME" -> java.sql.Types.TIME;
            case "TIME WITH TIME ZONE" -> java.sql.Types.TIME_WITH_TIMEZONE;
            case "TIMESTAMP WITH TIME ZONE", "DATETIME WITH TIME ZONE" -> java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
            default -> type.startsWith("TIMESTAMP") || type.startsWith("DATETIME") ? java.sql.Types.TIMESTAMP : java.sql.Types.VARCHAR;
        };
    }

    private static Integer intOrNull(ResultSet rs, int index) throws SQLException {
        int v = rs.getInt(index);
        return rs.wasNull() ? null : v;
    }
}
