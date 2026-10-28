package io.dataease.ee.sync.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.i18n.Translator;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Doris 分区配置（任务 target.targetProperty，与前端 DorisProperty.vue 字段一致）：
 *
 * <pre>
 * partitionEnable            on / off
 * partitionType              DateRange / NumberRange / List
 * partitionColumn            分区字段（须为 Key）
 * dynamicPartitionEnable     on / off（DateRange）
 * dynamicPartitionEnd        动态分区结束偏移量（预创建未来分区数）
 * dynamicPartitionTimeUnit   HOUR / DAY / WEEK / MONTH / YEAR
 * manualPartitionTimeRange   ["yyyy-MM-dd","yyyy-MM-dd"]（JSON 字符串或数组，含两端）
 * manualPartitionTimeUnit    HOUR / DAY / WEEK / MONTH / YEAR
 * manualPartitionStart / manualPartitionEnd / manualPartitionInterval   数值区间（含两端）与间隔
 * manualPartitionColumnValue 列表分区值，逗号分隔，每个值一个分区
 * buckets                    （可选，前端无此项）分桶数，缺省 AUTO；动态分区缺省 10
 * </pre>
 */
public record DorisPartition(String type, String column, boolean dynamic, Integer dynamicEnd, String dynamicUnit,
                             LocalDate rangeStart, LocalDate rangeEnd, String rangeUnit,
                             BigDecimal numberStart, BigDecimal numberEnd, BigDecimal numberInterval,
                             List<String> listValues, Integer buckets) {

    public static final String DATE_RANGE = "DateRange";
    public static final String NUMBER_RANGE = "NumberRange";
    public static final String LIST = "List";

    public static final int DEFAULT_DYNAMIC_BUCKETS = 10;

    /**
     * 单个分区语句允许生成的最大分区数（避免误配置产生海量分区）
     */
    public static final int MAX_PARTITIONS = 4096;

    private static final Set<String> TIME_UNITS = Set.of("HOUR", "DAY", "WEEK", "MONTH", "YEAR");

    static final Set<String> LIST_TYPES = Set.of("BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT", "DATE",
            "DATETIME", "DATEV2", "DATETIMEV2", "CHAR", "VARCHAR");
    static final Set<String> DATE_TYPES = Set.of("DATE", "DATETIME", "DATEV2", "DATETIMEV2");
    static final Set<String> NUMBER_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT");

    /**
     * 解析 targetProperty；未启用分区返回 null
     */
    public static DorisPartition parse(Target target) {
        if (target == null || StringUtils.isBlank(target.getTargetProperty())) {
            return null;
        }
        Map<String, Object> p;
        try {
            p = JsonUtil.parseObject(target.getTargetProperty(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw invalid(target.getTargetProperty());
        }
        if (p == null || !"on".equalsIgnoreCase(text(p.get("partitionEnable")))) {
            return null;
        }
        String type = text(p.get("partitionType"));
        String column = text(p.get("partitionColumn"));
        boolean dynamic = DATE_RANGE.equals(type) && "on".equalsIgnoreCase(text(p.get("dynamicPartitionEnable")));
        LocalDate start = null;
        LocalDate end = null;
        Object range = p.get("manualPartitionTimeRange");
        List<String> rangeValues = rangeValues(range);
        if (rangeValues.size() >= 2) {
            try {
                start = LocalDate.parse(StringUtils.left(rangeValues.get(0).trim(), 10));
                end = LocalDate.parse(StringUtils.left(rangeValues.get(1).trim(), 10));
            } catch (Exception e) {
                throw invalid(String.valueOf(range));
            }
        }
        List<String> values = new ArrayList<>(new LinkedHashSet<>(splitValues(text(p.get("manualPartitionColumnValue")))));
        return new DorisPartition(type, column, dynamic, integer(p.get("dynamicPartitionEnd")),
                upper(p.get("dynamicPartitionTimeUnit")), start, end, upper(p.get("manualPartitionTimeUnit")),
                decimal(p.get("manualPartitionStart")), decimal(p.get("manualPartitionEnd")), decimal(p.get("manualPartitionInterval")),
                values, integer(p.get("buckets")));
    }

    private static List<String> rangeValues(Object range) {
        if (range == null) {
            return List.of();
        }
        if (range instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = range.toString().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.startsWith("[")) {
            try {
                List<String> list = JsonUtil.parseList(text, new TypeReference<List<String>>() {
                });
                return list == null ? List.of() : list;
            } catch (Exception e) {
                throw invalid(text);
            }
        }
        // 兼容 "2023-09-08 - 2023-09-15" / "2023-09-08,2023-09-15"
        String[] parts = text.split("\\s+-\\s+|,|~");
        return parts.length >= 2 ? List.of(parts[0], parts[1]) : List.of();
    }

    static List<String> splitValues(String text) {
        List<String> values = new ArrayList<>();
        if (StringUtils.isBlank(text)) {
            return values;
        }
        for (String part : text.split("[,，]")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                values.add(v);
            }
        }
        return values;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String upper(Object value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    private static Integer integer(Object value) {
        BigDecimal d = decimal(value);
        return d == null ? null : d.intValue();
    }

    private static BigDecimal decimal(Object value) {
        String t = text(value);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw invalid(t);
        }
    }

    private static DEException invalid(String detail) {
        return new DEException(io.dataease.result.ResultCode.SYSTEM_INNER_ERROR.code(), Translator.get("ee_sync_doris_partition_invalid", detail));
    }

    public int bucketsOrDefault() {
        return buckets != null && buckets > 0 ? buckets : DEFAULT_DYNAMIC_BUCKETS;
    }

    /**
     * 校验分区配置与字段
     */
    public void validate(Target target) {
        if (!Set.of(DATE_RANGE, NUMBER_RANGE, LIST).contains(type)) {
            throw invalid("partitionType=" + type);
        }
        TableFieldDTO field = target.getFieldList() == null ? null : target.getFieldList().stream()
                .filter(f -> StringUtils.equals(f.getFieldName(), column)).findFirst().orElse(null);
        if (field == null) {
            throw invalid("partitionColumn=" + column);
        }
        if (!field.isFieldPk()) {
            DEException.throwException(Translator.get("ee_sync_doris_partition_key", column));
        }
        String fieldType = StringUtils.upperCase(StringUtils.substringBefore(StringUtils.trimToEmpty(field.getFieldType()), "("));
        Set<String> allowed = switch (type) {
            case DATE_RANGE -> DATE_TYPES;
            case NUMBER_RANGE -> NUMBER_TYPES;
            default -> LIST_TYPES;
        };
        if (!allowed.contains(fieldType)) {
            throw invalid(column + " " + fieldType + " / " + type);
        }
        if (buckets != null && (buckets <= 0 || buckets > 10000)) {
            throw invalid("buckets=" + buckets);
        }
        switch (type) {
            case DATE_RANGE -> {
                String unit = dynamic ? dynamicUnit : rangeUnit;
                if (!TIME_UNITS.contains(unit) || "HOUR".equals(unit) && fieldType.startsWith("DATE") && !fieldType.startsWith("DATETIME")) {
                    throw invalid("timeUnit=" + unit);
                }
                if (dynamic) {
                    if (dynamicEnd == null || dynamicEnd < 1) {
                        throw invalid("dynamicPartitionEnd=" + dynamicEnd);
                    }
                } else {
                    if (rangeStart == null || rangeEnd == null || rangeEnd.isBefore(rangeStart)) {
                        throw invalid("manualPartitionTimeRange");
                    }
                    if (countDatePartitions() > MAX_PARTITIONS) {
                        throw invalid(Translator.get("ee_sync_doris_partition_too_many", String.valueOf(MAX_PARTITIONS)));
                    }
                }
            }
            case NUMBER_RANGE -> {
                if (numberStart == null || numberEnd == null || numberInterval == null
                        || numberInterval.signum() <= 0 || numberEnd.compareTo(numberStart) < 0
                        || numberStart.stripTrailingZeros().scale() > 0 || numberEnd.stripTrailingZeros().scale() > 0
                        || numberInterval.stripTrailingZeros().scale() > 0) {
                    throw invalid("manualPartitionStart/End/Interval");
                }
                if (numberPartitionCount() > MAX_PARTITIONS) {
                    throw invalid(Translator.get("ee_sync_doris_partition_too_many", String.valueOf(MAX_PARTITIONS)));
                }
            }
            default -> {
                if (listValues.isEmpty()) {
                    throw invalid("manualPartitionColumnValue");
                }
                if (listValues.size() > MAX_PARTITIONS) {
                    throw invalid(Translator.get("ee_sync_doris_partition_too_many", String.valueOf(MAX_PARTITIONS)));
                }
            }
        }
    }

    private long countDatePartitions() {
        LocalDate to = rangeEnd.plusDays(1);
        return switch (rangeUnit) {
            case "HOUR" -> ChronoUnit.DAYS.between(rangeStart, to) * 24;
            case "WEEK" -> (ChronoUnit.DAYS.between(rangeStart, to) + 6) / 7;
            case "MONTH" -> ChronoUnit.MONTHS.between(rangeStart, alignedEnd()) ;
            case "YEAR" -> ChronoUnit.YEARS.between(rangeStart, alignedEnd());
            default -> ChronoUnit.DAYS.between(rangeStart, to);
        };
    }

    /**
     * 手动日期区间的右边界（不含）：结束日期次日，按间隔单位对齐到起点的整数倍
     */
    LocalDate alignedEnd() {
        LocalDate to = rangeEnd.plusDays(1);
        return switch (rangeUnit) {
            case "WEEK" -> rangeStart.plusWeeks((ChronoUnit.DAYS.between(rangeStart, to) + 6) / 7);
            case "MONTH" -> {
                long months = ChronoUnit.MONTHS.between(rangeStart, to);
                LocalDate end = rangeStart.plusMonths(months);
                yield end.isBefore(to) ? rangeStart.plusMonths(months + 1) : end;
            }
            case "YEAR" -> {
                long years = ChronoUnit.YEARS.between(rangeStart, to);
                LocalDate end = rangeStart.plusYears(years);
                yield end.isBefore(to) ? rangeStart.plusYears(years + 1) : end;
            }
            default -> to;
        };
    }

    private BigDecimal numberAlignedEnd() {
        BigDecimal span = numberEnd.subtract(numberStart).add(BigDecimal.ONE);
        BigDecimal count = span.divide(numberInterval, 0, RoundingMode.CEILING);
        return numberStart.add(count.multiply(numberInterval));
    }

    private long numberPartitionCount() {
        BigDecimal span = numberEnd.subtract(numberStart).add(BigDecimal.ONE);
        return span.divide(numberInterval, 0, RoundingMode.CEILING).longValue();
    }

    /**
     * PARTITION BY 子句
     *
     * @param quote     标识符引用
     * @param fieldType 分区字段的 Doris 类型
     */
    public String clause(UnaryOperator<String> quote, String fieldType) {
        String col = quote.apply(column);
        return switch (type) {
            case DATE_RANGE -> {
                if (dynamic) {
                    // 分区由动态分区调度按时间自动创建
                    yield "PARTITION BY RANGE(" + col + ") ()";
                }
                boolean datetime = StringUtils.upperCase(fieldType).startsWith("DATETIME");
                String from = rangeStart + (datetime ? " 00:00:00" : "");
                String to = alignedEnd() + (datetime ? " 00:00:00" : "");
                yield "PARTITION BY RANGE(" + col + ") (\n  FROM (\"" + from + "\") TO (\"" + to + "\") INTERVAL 1 " + rangeUnit + "\n)";
            }
            case NUMBER_RANGE -> "PARTITION BY RANGE(" + col + ") (\n  FROM (" + numberStart.toBigInteger() + ") TO ("
                    + numberAlignedEnd().toBigInteger() + ") INTERVAL " + numberInterval.toBigInteger() + "\n)";
            default -> {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < listValues.size(); i++) {
                    parts.add("PARTITION " + quote.apply("p" + i) + " VALUES IN (\"" + listValues.get(i).replace("\\", "\\\\").replace("\"", "\\\"") + "\")");
                }
                yield "PARTITION BY LIST(" + col + ") (\n  " + String.join(",\n  ", parts) + "\n)";
            }
        };
    }

    /**
     * 表属性（动态分区）
     */
    public Map<String, String> properties() {
        Map<String, String> props = new LinkedHashMap<>();
        if (dynamic) {
            props.put("dynamic_partition.enable", "true");
            props.put("dynamic_partition.time_unit", dynamicUnit);
            props.put("dynamic_partition.end", String.valueOf(dynamicEnd));
            props.put("dynamic_partition.prefix", "p");
            props.put("dynamic_partition.buckets", String.valueOf(bucketsOrDefault()));
        }
        return props;
    }
}
