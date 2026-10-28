package io.dataease.ee.datafilling.jdbc;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

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
     * Oracle/达梦的 CLOB 列：不能直接用于 =、IN、DISTINCT、ORDER BY
     */
    public boolean isLob(Integer jdbcType) {
        return isOracleLike() && jdbcType != null && (jdbcType == Types.CLOB || jdbcType == Types.NCLOB);
    }

    /**
     * 只用于 DISTINCT、ORDER BY 的列表达式：Oracle/达梦不能对 CLOB 去重或排序，只能取前 1000 个字符
     * （前 1000 个字符相同的不同长文本在选项列表中会合并为一项）。比较条件请使用 {@link #compare}。
     */
    public String sortable(String quotedColumn, Integer jdbcType) {
        if (isLob(jdbcType)) {
            return "DBMS_LOB.SUBSTR(" + quotedColumn + ", 1000, 1)";
        }
        return quotedColumn;
    }

    /**
     * 比较条件（含一个 {@code ?} 占位符），op 为 =、<>、<、<=、>、>=。
     * <p>Oracle/达梦的 CLOB 使用 {@code DBMS_LOB.COMPARE(col, ?) op 0} 精确比较全文，参数需以 {@link #lobParam} 绑定；
     * 列为 NULL 时 COMPARE 返回 NULL，条件为假，与普通列的 =、<> 语义一致。</p>
     */
    public String compare(String quotedColumn, Integer jdbcType, String op) {
        if (isLob(jdbcType)) {
            return "DBMS_LOB.COMPARE(" + quotedColumn + ", ?) " + op + " 0";
        }
        return quotedColumn + " " + op + " ?";
    }

    /**
     * IN / NOT IN 条件，count 个占位符；CLOB 列展开为多个精确比较的 OR
     */
    public String in(String quotedColumn, Integer jdbcType, int count, boolean not) {
        List<String> parts = new ArrayList<>();
        if (isLob(jdbcType)) {
            for (int i = 0; i < count; i++) {
                parts.add(compare(quotedColumn, jdbcType, "="));
            }
            return (not ? "NOT " : "") + "(" + String.join(" OR ", parts) + ")";
        }
        for (int i = 0; i < count; i++) {
            parts.add("?");
        }
        return quotedColumn + (not ? " NOT IN (" : " IN (") + String.join(", ", parts) + ")";
    }

    /**
     * 与 {@link #compare}、{@link #in} 配套的参数：CLOB 列的比较值以 CLOB 绑定（不受 VARCHAR2 4000 字节限制）
     */
    public Object lobParam(Object value, Integer jdbcType) {
        if (isLob(jdbcType) && value != null) {
            return new DfLob(String.valueOf(value));
        }
        return value;
    }

    /**
     * “为空”条件；非字符列只判断 NULL，Oracle/达梦中空字符串本身就是 NULL
     */
    public String emptyCondition(String quotedColumn, Integer jdbcType) {
        if (isOracleLike() || !isString(jdbcType)) {
            return quotedColumn + " IS NULL";
        }
        return "(" + quotedColumn + " IS NULL OR " + quotedColumn + " = '')";
    }

    public String notEmptyCondition(String quotedColumn, Integer jdbcType) {
        if (isOracleLike() || !isString(jdbcType)) {
            return quotedColumn + " IS NOT NULL";
        }
        return "(" + quotedColumn + " IS NOT NULL AND " + quotedColumn + " <> '')";
    }

    /**
     * 标识符长度的计量方式：Oracle、PostgreSQL、达梦按 UTF-8 字节计，其他按字符计
     */
    public int identifierLength(String name) {
        if (name == null) {
            return 0;
        }
        if (kind == Kind.ORACLE || kind == Kind.PG || kind == Kind.DM) {
            return name.getBytes(StandardCharsets.UTF_8).length;
        }
        return name.length();
    }

    /**
     * 以 CLOB 绑定的字符串参数
     */
    public record DfLob(String value) {
    }
}
