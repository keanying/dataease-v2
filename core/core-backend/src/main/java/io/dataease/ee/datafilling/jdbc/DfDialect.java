package io.dataease.ee.datafilling.jdbc;

import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * 按数据库产品区分的标识符引用与分页语法
 */
public class DfDialect {

    private final String product;

    private final String quoteStart;

    private final String quoteEnd;

    private final boolean fetchStyle;

    private DfDialect(String product, String quoteStart, String quoteEnd, boolean fetchStyle) {
        this.product = product;
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
            return new DfDialect(product, "`", "`", false);
        }
        if (product.contains("microsoft") || product.contains("sql server")) {
            return new DfDialect(product, "[", "]", true);
        }
        boolean fetch = product.contains("oracle") || product.contains("dm") || product.contains("db2");
        return new DfDialect(product, quote, quote, fetch);
    }

    public String getProduct() {
        return product;
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
        return product.contains("oracle");
    }
}
