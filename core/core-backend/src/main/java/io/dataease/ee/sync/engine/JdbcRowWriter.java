package io.dataease.ee.sync.engine;

import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.provider.SyncDialect;
import io.dataease.ee.sync.provider.SyncJdbcSupport;
import io.dataease.i18n.Translator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * JDBC 批量写入（可选先按 Key 删除再插入）；容错模式下批量失败后逐行写入并统计失败行
 */
public class JdbcRowWriter implements SyncRowWriter {

    private final Connection connection;
    private final SyncDialect dialect;
    private final PreparedStatement write;
    private final PreparedStatement delete;
    private final int[] keyIndexes;
    private final boolean tolerant;
    private final SyncJobContext context;
    private final Consumer<String> log;
    private final boolean autoCommit;
    /**
     * 各写入列 NULL 值的 JDBC 类型
     */
    private final int[] nullTypes;

    /**
     * @param columnTypes 写入列的目标类型（与 writeSql 的参数顺序一致），用于确定 NULL 值的 JDBC 类型
     */
    public JdbcRowWriter(Connection connection, SyncDialect dialect, String writeSql, String deleteSql, int[] keyIndexes,
                         List<String> columnTypes, boolean tolerant, SyncJobContext context, Consumer<String> log) throws SQLException {
        this.connection = connection;
        this.dialect = dialect;
        this.keyIndexes = keyIndexes;
        this.nullTypes = columnTypes.stream().mapToInt(dialect::nullType).toArray();
        this.tolerant = tolerant;
        this.context = context;
        this.log = log;
        this.autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        PreparedStatement w = null;
        try {
            w = connection.prepareStatement(writeSql);
            this.delete = deleteSql == null ? null : connection.prepareStatement(deleteSql);
        } catch (SQLException e) {
            if (w != null) {
                w.close();
            }
            connection.setAutoCommit(autoCommit);
            throw e;
        }
        this.write = w;
    }

    @Override
    public int batchRows() {
        return SyncConstants.BATCH_SIZE;
    }

    @Override
    public long write(List<Object[]> batch) throws SQLException {
        try {
            if (delete != null) {
                for (Object[] row : batch) {
                    for (int i = 0; i < keyIndexes.length; i++) {
                        bind(delete, i + 1, row[keyIndexes[i]], keyIndexes[i]);
                    }
                    delete.addBatch();
                }
                delete.executeBatch();
            }
            for (Object[] row : batch) {
                for (int i = 0; i < row.length; i++) {
                    bind(write, i + 1, row[i], i);
                }
                write.addBatch();
            }
            write.executeBatch();
            connection.commit();
            return 0;
        } catch (SQLException e) {
            safeRollback();
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
                            bind(delete, i + 1, row[keyIndexes[i]], keyIndexes[i]);
                        }
                        delete.executeUpdate();
                    }
                    for (int i = 0; i < row.length; i++) {
                        bind(write, i + 1, row[i], i);
                    }
                    write.executeUpdate();
                    connection.commit();
                } catch (SQLException rowError) {
                    safeRollback();
                    failed++;
                }
            }
            return failed;
        }
    }

    private void bind(PreparedStatement ps, int index, Object raw, int column) throws SQLException {
        Object value = dialect.writeValue(raw);
        if (value == null) {
            ps.setNull(index, column < nullTypes.length ? nullTypes[column] : dialect.nullType());
        } else if (value instanceof LocalDateTime ldt) {
            ps.setTimestamp(index, Timestamp.valueOf(ldt));
        } else {
            ps.setObject(index, value);
        }
    }

    private void safeRollback() {
        try {
            connection.rollback();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            write.close();
            if (delete != null) {
                delete.close();
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
