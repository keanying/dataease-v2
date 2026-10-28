package io.dataease.ee.sync.provider;

/**
 * 同步执行的终止控制：供非 JDBC 读写（HTTP）检查终止标记并登记可中止的进行中请求
 */
public interface SyncCancellation {

    SyncCancellation NONE = new SyncCancellation() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void bindAbort(Runnable abort) {
        }
    };

    boolean isCancelled();

    /**
     * 登记当前请求的中止动作，null 解除
     */
    void bindAbort(Runnable abort);
}
