package io.dataease.ee.datafilling.manage;

import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 填报数据的类型转换
 */
public final class DfValues {

    private static final String[] DATE_PATTERNS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd", "yyyy/MM/dd", "yyyy-MM", "yyyy/MM", "yyyy"
    };

    private DfValues() {
    }

    public static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return StringUtils.isBlank(s);
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return false;
    }

    /**
     * 写入数据库前的转换
     */
    public static Object toDb(Object value, ExtTableField.BaseType type, String label) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            value = DfJson.write(value);
        }
        ExtTableField.BaseType t = type == null ? ExtTableField.BaseType.nvarchar : type;
        switch (t) {
            case datetime -> {
                if (isEmpty(value)) {
                    return null;
                }
                Long ms = toMillis(value);
                if (ms == null) {
                    DEException.throwException(Translator.get("ee_df_value_invalid_date", label, String.valueOf(value)));
                }
                return new Timestamp(ms);
            }
            case number -> {
                if (isEmpty(value)) {
                    return null;
                }
                BigDecimal d = toDecimal(value, label);
                return d.setScale(0, RoundingMode.HALF_UP).longValue();
            }
            case decimal -> {
                if (isEmpty(value)) {
                    return null;
                }
                return toDecimal(value, label);
            }
            default -> {
                if (value instanceof Number n && !(value instanceof BigDecimal)) {
                    return String.valueOf(n);
                }
                if (value instanceof BigDecimal d) {
                    return d.stripTrailingZeros().toPlainString();
                }
                return String.valueOf(value);
            }
        }
    }

    private static BigDecimal toDecimal(Object value, String label) {
        try {
            if (value instanceof BigDecimal d) {
                return d;
            }
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            return new BigDecimal(StringUtils.trim(String.valueOf(value)));
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_df_value_invalid_number", label, String.valueOf(value)));
        }
        return null;
    }

    public static Long toMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Date d) {
            return d.getTime();
        }
        String s = StringUtils.trim(String.valueOf(value));
        if (s.isEmpty()) {
            return null;
        }
        if (s.matches("^-?\\d{10,}$")) {
            return Long.parseLong(s);
        }
        for (String pattern : DATE_PATTERNS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                sdf.setLenient(false);
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                Date d = sdf.parse(s, pos);
                if (d != null && pos.getIndex() == s.length()) {
                    return d.getTime();
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /**
     * 主键值按列类型转换
     */
    public static Object pkValue(Object value, Integer sqlType) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (sqlType != null) {
            switch (sqlType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                    try {
                        return Long.parseLong(s);
                    } catch (Exception e) {
                        return s;
                    }
                }
                case Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.REAL -> {
                    try {
                        return new BigDecimal(s);
                    } catch (Exception e) {
                        return s;
                    }
                }
                default -> {
                    return s;
                }
            }
        }
        return s;
    }

    /**
     * 多选值转为 JSON 数组字符串
     */
    public static String toJsonArray(Object value) {
        if (isEmpty(value)) {
            return null;
        }
        if (value instanceof Collection<?> c) {
            return DfJson.write(c);
        }
        String s = String.valueOf(value).trim();
        if (s.startsWith("[")) {
            return s;
        }
        List<String> list = new ArrayList<>();
        for (String one : s.split("[;,；，]")) {
            if (StringUtils.isNotBlank(one)) {
                list.add(one.trim());
            }
        }
        return DfJson.write(list);
    }

    public static String formatDate(Object value, String dateType) {
        Long ms = toMillis(value);
        if (ms == null) {
            return value == null ? null : String.valueOf(value);
        }
        String pattern = switch (StringUtils.defaultString(dateType)) {
            case "year" -> "yyyy";
            case "month", "monthrange" -> "yyyy-MM";
            case "datetime", "datetimerange" -> "yyyy-MM-dd HH:mm:ss";
            default -> "yyyy-MM-dd";
        };
        return new SimpleDateFormat(pattern).format(new Date(ms));
    }
}
