package io.dataease.ee.sync.engine;

import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.sync.manage.SyncDatasourceManage;
import io.dataease.ee.sync.manage.SyncTaskManage;
import io.dataease.utils.LogUtil;
import org.springframework.context.annotation.Lazy;

import java.util.Map;

/**
 * 核心扩展点：
 * <ul>
 *     <li>{@code dataSyncTaskExecutor}：Quartz 触发同步任务（{@code execute(Map)}）与启动时恢复调度（{@code init()}）；</li>
 *     <li>{@code syncDatasourceStatus}：核心定时检查数据源状态后，同时检查同步数据源状态。</li>
 * </ul>
 */
@EeExtension({"dataSyncTaskExecutor", "syncDatasourceStatus"})
public class DataSyncTaskExecutorExtension {

    private final SyncTaskRunner runner;
    private final SyncTaskManage taskManage;
    private final SyncDatasourceManage datasourceManage;

    public DataSyncTaskExecutorExtension(@Lazy SyncTaskRunner runner, @Lazy SyncTaskManage taskManage,
                                         @Lazy SyncDatasourceManage datasourceManage) {
        this.runner = runner;
        this.taskManage = taskManage;
        this.datasourceManage = datasourceManage;
    }

    /**
     * Quartz 线程中同步执行（任务类禁止并发，同一任务不会重叠）
     */
    public boolean execute(Map<String, Object> taskData) {
        Object taskId = taskData == null ? null : taskData.get("taskId");
        if (taskId == null) {
            return false;
        }
        try {
            return runner.run(Long.valueOf(taskId.toString()), SyncTaskRunner.TRIGGER_SCHEDULE);
        } catch (Exception e) {
            LogUtil.error("execute sync task failed: " + taskId + ", " + e.getMessage(), e);
            return false;
        }
    }

    public void init() {
        taskManage.init();
    }

    public void updateDatasourceStatus() {
        try {
            datasourceManage.checkAllStatus();
        } catch (Exception e) {
            LogUtil.error("check sync datasource status failed: " + e.getMessage(), e);
        }
    }
}
