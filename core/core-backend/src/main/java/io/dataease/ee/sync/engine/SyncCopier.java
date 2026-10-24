package io.dataease.ee.sync.engine;

import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.provider.SyncDialect;
import io.dataease.ee.sync.provider.SyncJdbcSupport;
import io.dataease.ee.sync.provider.SyncProviderDefaults;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.utils.ProviderUtil;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 单次同步执行：源端流式读取 → 目标端分批写入。
 *
 * <ul>
 *     <li>全量：写入临时表 like_xxx，成功后替换目标表，失败时目标表保持不变；</li>
 *     <li>增量：读取目标表增量字段最大值（加偏移量）作为条件，配置了 Key 时按 Key 更新写入；目标表不存在时先建表全量写入。</li>
 * </ul>
 */
public class SyncCopier {

    /**
     * 执行结果
     */
    public record Result(long rows, long failedRows, boolean tableCreated) {
    }

    /**
     * 用户终止
     */
    public static class TerminatedException extends RuntimeException {
        public TerminatedException() {
            super("terminated");
        }
    }

    /**
     * 执行超时
     */
    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String msg) {
            super(msg);
        }
    }

    private final SyncDialect sourceDialect;
    private final SyncDialect targetDialect;
    private final DatasourceConfiguration sourceCfg;
    private final DatasourceConfiguration targetCfg;
    private final Source source;
    private final Target target;
    private final SyncJobContext context;
    private final Consumer<String> log;
    private final long deadline;

    private long lastProgressLog = 0;

    public SyncCopier(SyncDialect sourceDialect, DatasourceConfiguration sourceCfg, SyncDialect targetDialect,
                      DatasourceConfiguration targetCfg, Source source, Target target, SyncJobContext context,
                      Consumer<String> log, long deadline) {
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
        this.sourceCfg = sourceCfg;
        this.targetCfg = targetCfg;
        this.source = source;
        this.target = target;
        this.context = context;
        this.log = log;
        this.deadline = deadline;
    }

    public Result run() throws SQLException {
        List<TableFieldDTO> fields = target.getFieldList().stream()
                .filter(f -> StringUtils.isNotBlank(f.getFieldName()))
                .toList();
        List<String> targetCols = fields.stream().map(TableFieldDTO::getFieldName).toList();
        List<String> sourceCols = fields.stream().map(f -> StringUtils.defaultIfBlank(f.getFieldSource(), f.getFieldName())).toList();
        List<String> keys = fields.stream().filter(TableFieldDTO::isFieldPk).map(TableFieldDTO::getFieldName).toList();
        String table = target.getTableName();
        boolean increment = SyncConstants.SWITCH_ON.equalsIgnoreCase(target.getIncrementSync());
        double tolerance = faultTolerance();

        try (Connection src = sourceDialect.open(sourceCfg); Connection tgt = targetDialect.open(targetCfg)) {
            boolean exists = targetDialect.tableExists(tgt, targetCfg, table);
            String writeTable;
            boolean replace = false;
            boolean created = false;
            String incrementSourceCol = null;
            Object incrementValue = null;
            if (increment && exists) {
                TableFieldDTO incField = fields.stream().filter(f -> StringUtils.equals(f.getFieldName(), target.getIncrementField()))
                        .findFirst().orElse(null);
                if (incField == null) {
                    throw new IllegalStateException(Translator.get("ee_sync_task_increment_field_invalid"));
                }
                Object max = targetDialect.maxValue(tgt, targetCfg, table, incField.getFieldName());
                if (max != null) {
                    incrementValue = applyOffset(max, target.getIncrementOffset() == null ? 0 : target.getIncrementOffset(),
                            target.getIncrementOffsetUnit());
                    incrementSourceCol = StringUtils.defaultIfBlank(incField.getFieldSource(), incField.getFieldName());
                    log.accept(Translator.get("ee_sync_log_increment", incField.getFieldName(), String.valueOf(max), String.valueOf(incrementValue)));
                } else {
                    log.accept(Translator.get("ee_sync_log_increment_empty"));
                }
                writeTable = table;
            } else if (increment) {
                log.accept(Translator.get("ee_sync_log_create_table", table));
                createTable(tgt, table);
                created = true;
                writeTable = table;
            } else {
                writeTable = targetDialect.tempTableName(table);
                targetDialect.dropTable(tgt, targetCfg, writeTable);
                log.accept(Translator.get("ee_sync_log_create_temp", writeTable));
                createTable(tgt, writeTable);
                replace = true;
            }

            String selectSql = sourceDialect.selectSql(sourceCfg, source, sourceCols, incrementSourceCol);
            log.accept(Translator.get("ee_sync_log_query", selectSql));
            String writeSql;
            String deleteSql = null;
            boolean upsert = increment && exists && !keys.isEmpty();
            if (upsert) {
                writeSql = targetDialect.upsertSql(tgt, targetCfg, writeTable, targetCols, keys);
                if (writeSql == null) {
                    writeSql = targetDialect.insertSql(targetCfg, writeTable, targetCols);
                    deleteSql = "DELETE FROM " + targetDialect.qualified(targetCfg, writeTable) + " WHERE "
                            + keys.stream().map(k -> targetDialect.quote(k) + " = ?").collect(Collectors.joining(" AND "));
                }
            } else {
                writeSql = targetDialect.insertSql(targetCfg, writeTable, targetCols);
            }
            int[] keyIndexes = keys.stream().mapToInt(targetCols::indexOf).toArray();

            long rows = 0;
            long failed = 0;
            try {
                boolean autoCommit = tgt.getAutoCommit();
                tgt.setAutoCommit(false);
                // PostgreSQL 等驱动仅在非自动提交下按 fetchSize 流式读取
                src.setAutoCommit(false);
                try (PreparedStatement read = src.prepareStatement(selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                     PreparedStatement write = tgt.prepareStatement(writeSql);
                     PreparedStatement delete = deleteSql == null ? null : tgt.prepareStatement(deleteSql)) {
                    context.bind(read);
                    read.setFetchSize(sourceDialect.fetchSize());
                    int remaining = remainingSeconds();
                    if (remaining > 0) {
                        read.setQueryTimeout(remaining);
                    }
                    if (incrementValue != null) {
                        read.setObject(1, incrementValue);
                    }
                    try (ResultSet rs = read.executeQuery()) {
                        int columnCount = targetCols.size();
                        List<Object[]> batch = new ArrayList<>(SyncConstants.BATCH_SIZE);
                        while (rs.next()) {
                            Object[] row = new Object[columnCount];
                            for (int i = 0; i < columnCount; i++) {
                                row[i] = sourceDialect.readValue(rs, i + 1);
                            }
                            batch.add(row);
                            if (batch.size() >= SyncConstants.BATCH_SIZE) {
                                failed += flush(tgt, write, delete, keyIndexes, batch, tolerance > 0);
                                rows += batch.size();
                                batch.clear();
                                checkpoint(rows, failed, tolerance);
                            }
                        }
                        if (!batch.isEmpty()) {
                            failed += flush(tgt, write, delete, keyIndexes, batch, tolerance > 0);
                            rows += batch.size();
                            batch.clear();
                        }
                    }
                } finally {
                    context.bind(null);
                    tgt.setAutoCommit(autoCommit);
                }
                checkFailures(rows, failed, tolerance);
                if (context.isCancelled()) {
                    throw new TerminatedException();
                }
                log.accept(Translator.get("ee_sync_log_written", String.valueOf(rows - failed), String.valueOf(failed)));
                if (replace) {
                    targetDialect.replaceTable(tgt, targetCfg, writeTable, table);
                    log.accept(Translator.get("ee_sync_log_replaced", table));
                    created = true;
                }
                return new Result(rows - failed, failed, created);
            } catch (RuntimeException | SQLException e) {
                if (replace) {
                    try {
                        targetDialect.dropTable(tgt, targetCfg, writeTable);
                    } catch (Exception ignored) {
                    }
                }
                if (context.isCancelled()) {
                    throw new TerminatedException();
                }
                throw e;
            }
        }
    }

    private void createTable(Connection tgt, String table) throws SQLException {
        for (String sql : targetDialect.createTableStatements(tgt, targetCfg, table, target)) {
            if (StringUtils.isBlank(sql)) {
                continue;
            }
            for (String part : sql.split(SyncProviderDefaults.STATEMENT_SEPARATOR)) {
                if (StringUtils.isNotBlank(part)) {
                    try (java.sql.Statement st = tgt.createStatement()) {
                        st.execute(part);
                    }
                }
            }
        }
        if (!tgt.getAutoCommit()) {
            tgt.commit();
        }
    }

    /**
     * 写入一批；容错模式下批量失败后逐行写入并统计失败行
     */
    private long flush(Connection tgt, PreparedStatement write, PreparedStatement delete, int[] keyIndexes,
                       List<Object[]> batch, boolean tolerant) throws SQLException {
        try {
            if (delete != null) {
                for (Object[] row : batch) {
                    for (int i = 0; i < keyIndexes.length; i++) {
                        bind(delete, i + 1, row[keyIndexes[i]]);
                    }
                    delete.addBatch();
                }
                delete.executeBatch();
            }
            for (Object[] row : batch) {
                for (int i = 0; i < row.length; i++) {
                    bind(write, i + 1, row[i]);
                }
                write.addBatch();
            }
            write.executeBatch();
            tgt.commit();
            return 0;
        } catch (SQLException e) {
            safeRollback(tgt);
            write.clearBatch();
            if (delete != null) {
                delete.clearBatch();
            }
            if (!tolerant || context.isCancelled()) {
                throw e;
            }
            log.accept(Translator.get("ee_sync_log_batch_failed", SyncJdbcSupport.rootMessage(e)));
            long failed = 0;
            for (Object[] row : batch) {
                try {
                    if (delete != null) {
                        for (int i = 0; i < keyIndexes.length; i++) {
                            bind(delete, i + 1, row[keyIndexes[i]]);
                        }
                        delete.executeUpdate();
                    }
                    for (int i = 0; i < row.length; i++) {
                        bind(write, i + 1, row[i]);
                    }
                    write.executeUpdate();
                    tgt.commit();
                } catch (SQLException rowError) {
                    safeRollback(tgt);
                    failed++;
                }
            }
            return failed;
        }
    }

    private void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, targetDialect.nullType());
        } else if (value instanceof LocalDateTime ldt) {
            ps.setTimestamp(index, Timestamp.valueOf(ldt));
        } else {
            ps.setObject(index, value);
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }

    private void checkpoint(long rows, long failed, double tolerance) {
        if (context.isCancelled()) {
            throw new TerminatedException();
        }
        if (deadline > 0 && System.currentTimeMillis() > deadline) {
            throw new TimeoutException(Translator.get("ee_sync_log_timeout"));
        }
        checkFailures(rows, failed, tolerance);
        long now = System.currentTimeMillis();
        if (now - lastProgressLog >= 3000) {
            lastProgressLog = now;
            log.accept(Translator.get("ee_sync_log_progress", String.valueOf(rows)));
        }
    }

    private void checkFailures(long rows, long failed, double tolerance) {
        if (failed > 0 && rows > 0 && (double) failed / rows > tolerance) {
            throw new IllegalStateException(Translator.get("ee_sync_log_fault_exceeded", String.valueOf(failed), String.valueOf(tolerance)));
        }
    }

    private int remainingSeconds() {
        if (deadline <= 0) {
            return 0;
        }
        long remaining = (deadline - System.currentTimeMillis()) / 1000;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining));
    }

    private double faultTolerance() {
        double rate = target.getFaultToleranceRate();
        if (StringUtils.isNotBlank(target.getTargetProperty())) {
            try {
                Map<String, Object> property = JsonUtil.parseObject(target.getTargetProperty(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                Object value = property == null ? null : property.get("faultToleranceRate");
                if (value != null && StringUtils.isNotBlank(value.toString())) {
                    rate = Math.max(rate, Double.parseDouble(value.toString()));
                }
            } catch (Exception ignored) {
            }
        }
        return Math.max(0, Math.min(1, rate));
    }

    /**
     * 增量起点 = 目标表最大值 + 偏移量（负数为前向偏移）
     */
    public static Object applyOffset(Object max, long offset, String unit) {
        if (max == null || offset == 0) {
            return max instanceof LocalDateTime ldt ? Timestamp.valueOf(ldt) : max;
        }
        String u = StringUtils.defaultIfBlank(unit, "second").toLowerCase();
        if (max instanceof Number number) {
            return new BigDecimal(number.toString()).add(BigDecimal.valueOf(offset));
        }
        if (max instanceof java.sql.Date date) {
            return java.sql.Date.valueOf(plus(date.toLocalDate().atStartOfDay(), offset, u).toLocalDate());
        }
        if (max instanceof LocalDate date) {
            return java.sql.Date.valueOf(plus(date.atStartOfDay(), offset, u).toLocalDate());
        }
        if (max instanceof Timestamp ts) {
            return Timestamp.valueOf(plus(ts.toLocalDateTime(), offset, u));
        }
        if (max instanceof LocalDateTime ldt) {
            return Timestamp.valueOf(plus(ldt, offset, u));
        }
        if (max instanceof OffsetDateTime odt) {
            return Timestamp.valueOf(plus(odt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime(), offset, u));
        }
        if (max instanceof java.util.Date date) {
            return Timestamp.valueOf(plus(new Timestamp(date.getTime()).toLocalDateTime(), offset, u));
        }
        return ProviderUtil.calculateDateOffset(max.toString(), offset, u);
    }

    private static LocalDateTime plus(LocalDateTime time, long offset, String unit) {
        return switch (unit) {
            case "millisecond" -> time.plusNanos(offset * 1_000_000L);
            case "minute" -> time.plusMinutes(offset);
            case "hour" -> time.plusHours(offset);
            case "day" -> time.plusDays(offset);
            case "month" -> time.plusMonths(offset);
            case "year" -> time.plusYears(offset);
            default -> time.plusSeconds(offset);
        };
    }
}
