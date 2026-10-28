package io.dataease.ee.sync.provider;

import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;

import java.util.List;

/**
 * 非 JDBC 源端（如 Elasticsearch）：由提供者自行分页读取记录，引擎只负责写入。
 */
public interface SyncRecordSource {

    /**
     * 行处理
     */
    @FunctionalInterface
    interface RowHandler {
        void accept(Object[] row) throws Exception;
    }

    /**
     * 保存任务时校验源端配置（抽取方式、查询参数等）
     */
    void validateSource(Source source);

    /**
     * 执行日志中展示的查询描述
     */
    String describe(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn, Object incrementValue);

    /**
     * 按列读取全部（或大于增量值的）记录；incrementColumn 为空表示全量
     */
    void read(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn,
              Object incrementValue, SyncCancellation cancellation, RowHandler handler) throws Exception;
}
