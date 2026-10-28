package io.dataease.ee.sync.engine;

import java.util.List;

/**
 * 目标端分批写入
 */
public interface SyncRowWriter extends AutoCloseable {

    /**
     * 写入一批，返回失败（被过滤）的行数；超出容错或不可恢复时抛出异常
     */
    long write(List<Object[]> batch) throws Exception;

    /**
     * 每批行数
     */
    int batchRows();

    /**
     * 每批估算字节上限（0 不限）
     */
    default long batchBytes() {
        return 0;
    }

    @Override
    default void close() throws Exception {
    }
}
