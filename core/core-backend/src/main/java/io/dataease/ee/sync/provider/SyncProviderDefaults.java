package io.dataease.ee.sync.provider;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;

/**
 * 方言通用实现
 */
public final class SyncProviderDefaults {

    public static final int FETCH_SIZE = 2000;

    /**
     * 多条 DDL 之间的分隔符
     */
    public static final String STATEMENT_SEPARATOR = "\n;\n";

    private SyncProviderDefaults() {
    }

    /**
     * 读取源端值并转换为各驱动都能接受的 Java 类型（大对象读为字符串/字节数组）
     */
    public static Object readValue(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) > 0) {
                    sb.append(buf, 0, len);
                }
                return sb.toString();
            } catch (java.io.IOException e) {
                throw new SQLException(e);
            }
        }
        if (value instanceof Blob blob) {
            long length = blob.length();
            return blob.getBytes(1, (int) Math.min(length, Integer.MAX_VALUE));
        }
        if (value instanceof SQLXML xml) {
            return xml.getString();
        }
        if (value instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (value instanceof java.util.UUID uuid) {
            // Oracle 等驱动不支持绑定 java.util.UUID
            return uuid.toString();
        }
        if (value instanceof java.sql.Array || value instanceof Struct) {
            return rs.getString(index);
        }
        String className = value.getClass().getName();
        if (className.startsWith("oracle.sql.") || className.startsWith("oracle.jdbc.")) {
            // Oracle 专有类型（TIMESTAMP/TIMESTAMPTZ/INTERVAL 等）按标准类型读取
            if (className.contains("TIMESTAMP") || className.contains("DATE")) {
                return rs.getTimestamp(index);
            }
            return rs.getString(index);
        }
        if (className.startsWith("org.postgresql.util.PG")) {
            return rs.getString(index);
        }
        if (className.startsWith("microsoft.sql.DateTimeOffset")) {
            return rs.getTimestamp(index);
        }
        return value;
    }
}
