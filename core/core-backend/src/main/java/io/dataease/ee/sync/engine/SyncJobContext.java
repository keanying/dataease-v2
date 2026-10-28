package io.dataease.ee.sync.engine;

import lombok.Getter;

import java.sql.Statement;

/**
 * 一次同步执行的运行时上下文（终止标记、当前语句 / HTTP 请求）
 */
public class SyncJobContext implements io.dataease.ee.sync.provider.SyncCancellation {

    @Getter
    private final Long taskId;
    @Getter
    private final Long logId;
    private volatile boolean cancelled;
    private volatile Statement statement;
    private volatile Runnable abort;

    public SyncJobContext(Long taskId, Long logId) {
        this.taskId = taskId;
        this.logId = logId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    public void bind(Statement statement) {
        this.statement = statement;
    }

    /**
     * 绑定当前进行中的非 JDBC 操作（如 HTTP 请求）的中止动作，null 解除绑定
     */
    @Override
    public void bindAbort(Runnable abort) {
        this.abort = abort;
        if (abort != null && cancelled) {
            abort.run();
        }
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
        Runnable currentAbort = abort;
        if (currentAbort != null) {
            try {
                currentAbort.run();
            } catch (Exception ignored) {
            }
        }
    }
}
