package io.dataease.ee.datafilling.manage;

import io.dataease.ee.interact.EeExtension;
import io.dataease.utils.LogUtil;

import java.util.Map;

/**
 * 核心 {@code DeDataFillingTaskExecutor} 的企业版实现：定时下发填报任务
 */
@EeExtension("dataFillingTaskExecutor")
public class DfTaskExecutorExtension {

    private final DfTaskManage taskManage;

    public DfTaskExecutorExtension(DfTaskManage taskManage) {
        this.taskManage = taskManage;
    }

    /**
     * @return 任务是否仍需保留调度
     */
    public boolean execute(Map<String, Object> taskData) {
        Object id = taskData == null ? null : taskData.get("taskId");
        if (id == null) {
            return false;
        }
        try {
            return taskManage.executeScheduled(Long.valueOf(id.toString()));
        } catch (Exception e) {
            LogUtil.error("execute data filling task failed: " + e.getMessage(), e);
            return true;
        }
    }

    public void init() {
        taskManage.init();
    }
}
