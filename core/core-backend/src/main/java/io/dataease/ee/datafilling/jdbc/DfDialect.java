package io.dataease.ee.datafilling.jdbc;

import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Types;

/**
 * 按数据库产品区分的标识符引用、分页、空值绑定与比较表达式
 */
public class DfDialect {

    /**
     * 数据库产品分类
     */
    public enum Kind {
        MYSQL, PG, SQLSERVER, ORACLE, DM, OTHER
    }

    private final String product;

    private final Kind kind;

    private final String quoteStart;

    private final String quoteEnd;

    private final boolean fetchStyle;

    private DfDialect(String product, Kind kind, String quoteStart, String quoteEnd, boolean fetchStyle) {
        this.product = product;
        this.kind = kind;
        this.quoteStart = quoteStart;
        this.quoteEnd = quoteEnd;
        this.fetchStyle = fetchStyle;
    }

    public static DfDialect of(Connection connection) {
        String product = "";
        String quote = "\"";
        try {
            DatabaseMetaData meta = connection.getMetaData();
            product = StringUtils.defaultString(meta.getDatabaseProductName()).toLowerCase();
            String q = meta.getIdentifierQuoteString();
            if (StringUtils.isNotBlank(q)) {
                quote = q.trim();
            }
        } catch (Exception ignore) {
        }
        if (product.contains("mysql") || product.contains("mariadb")) {
            return new DfDialect(product, Kind.MYSQL, "`", "`", false);
        }
        if (product.contains("microsoft") || product.contains("sql server")) {
            return new DfDialect(product, Kind.SQLSERVER, "[", "]", true);
        }
        if (product.contains("oracle")) {
            return new DfDialect(product, Kind.ORACLE, "\"", "\"", true);
        }
        if (product.startsWith("dm") || product.contains("dameng")) {
            return new DfDialect(product, Kind.DM, "\"", "\"", true);
        }
        if (product.contains("postgres") || product.contains("kingbase")) {
            return new DfDialect(product, Kind.PG, "\"", "\"", false);
        }
        boolean fetch = product.contains("db2");
        return new DfDialect(product, Kind.OTHER, quote, quote, fetch);
    }

    public String getProduct() {
        return product;
    }

    public Kind kind() {
        return kind;
    }

    public String quote(String name) {
        return quoteStart + StringUtils.replace(name, quoteEnd, quoteEnd + quoteEnd) + quoteEnd;
    }

    /**
     * 追加分页子句（调用方需保证 SQL 已带 ORDER BY）
     */
    public String page(String sql, long limit, long offset) {
        if (limit <= 0) {
            return sql;
        }
        if (fetchStyle) {
            return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    public boolean isOracle() {
        return kind == Kind.ORACLE;
    }

    public boolean isSqlServer() {
        return kind == Kind.SQLSERVER;
    }

    /**
     * Oracle / 达梦：空字符串即 NULL，CLOB 不能直接比较
     */
    public boolean isOracleLike() {
        return kind == Kind.ORACLE || kind == Kind.DM;
    }

    /**
     * 绑定 NULL 参数时使用的 JDBC 类型：Oracle 驱动不接受 Types.NULL，SQL Server 会把 Types.NULL 当作 varbinary
     * （不能隐式转换为 datetime2 等类型），二者都使用可隐式转换为任意类型的 VARCHAR
     */
    public int nullType() {
        return isOracleLike() || isSqlServer() ? Types.VARCHAR : Types.NULL;
    }

    public static boolean isString(Integer jdbcType) {
        if (jdbcType == null) {
            return true;
        }
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    /**
     * 用于 =、IN、DISTINCT、ORDER BY 的列表达式：Oracle/达梦的 CLOB 取前 1000 个字符比较
     */
    public String comparable(String quotedColumn, Integer jdbcType) {
        if (isOracleLike() && jdbcType != null && (jdbcType == Types.CLOB || jdbcType == Types.NCLOB)) {
            return "DBMS_LOB.SUBSTR(" + quotedColumn + ", 1000, 1)";
        }
        return quotedColumn;
    }

    /**
     * “为空”条件；非字符列只判断 NULL，Oracle/达梦中空字符串本身就是 NULL
     */
    public String emptyCondition(String quotedColumn, Integer jdbcType) {
        if (isOracleLike() || !isString(jdbcType)) {
            return quotedColumn + " IS NULL";
        }
        return "(" + quotedColumn + " IS NULL OR " + comparable(quotedColumn, jdbcType) + " = '')";
    }

    public String notEmptyCondition(String quotedColumn, Integer jdbcType) {
        if (isOracleLike() || !isString(jdbcType)) {
            return quotedColumn + " IS NOT NULL";
        }
        return "(" + quotedColumn + " IS NOT NULL AND " + comparable(quotedColumn, jdbcType) + " <> '')";
    }
}
