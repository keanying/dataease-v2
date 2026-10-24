package io.dataease.ee.sync.engine;

import lombok.Getter;

import java.sql.Statement;

/**
 * 一次同步执行的运行时上下文（终止标记、当前语句）
 */
public class SyncJobContext {

    @Getter
    private final Long taskId;
    @Getter
    private final Long logId;
    private volatile boolean cancelled;
    private volatile Statement statement;

    public SyncJobContext(Long taskId, Long logId) {
        this.taskId = taskId;
        this.logId = logId;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void bind(Statement statement) {
        this.statement = statement;
    }

    public void cancel() {
        cancelled = true;
        Statement current = statement;
        if (current != null) {
            try {
                current.cancel();
            } catch (Exception ignored) {
            }
        }
    }
}
