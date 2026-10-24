package io.dataease.ee.report;

import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.threshold.ThresholdManage;
import io.dataease.ee.threshold.ThresholdRunner;
import io.dataease.job.schedule.DeTaskExecutor;
import io.dataease.utils.LogUtil;
import org.springframework.context.annotation.Lazy;

import java.util.Map;

/**
 * 核心 {@link DeTaskExecutor#execute(Map)} / {@link DeTaskExecutor#init()}（{@code @XpackInteract("xpackTaskExecutor")}）的实现：
 * Quartz 触发 {@code DeXpackScheduleJob} 后在这里分发到定时报告或阈值告警。
 *
 * <p>JobDataMap：报告任务 {@code taskId, isTempTask, isRetryTask}；告警任务额外带 {@code threshold}。</p>
 */
@EeExtension("xpackTaskExecutor")
public class EeTaskExecutorExtension {

    private final ReportRunner reportRunner;

    private final ReportManage reportManage;

    private final ThresholdRunner thresholdRunner;

    private final ThresholdManage thresholdManage;

    private final DeTaskExecutor deTaskExecutor;

    public EeTaskExecutorExtension(ReportRunner reportRunner, ReportManage reportManage, ThresholdRunner thresholdRunner,
                                   ThresholdManage thresholdManage, @Lazy DeTaskExecutor deTaskExecutor) {
        this.reportRunner = reportRunner;
        this.reportManage = reportManage;
        this.thresholdRunner = thresholdRunner;
        this.thresholdManage = thresholdManage;
        this.deTaskExecutor = deTaskExecutor;
    }

    public boolean execute(Map<String, Object> taskData) {
        Long taskId = toLong(taskData.get("taskId"));
        if (taskId == null) {
            return false;
        }
        if (taskData.containsKey("threshold")) {
            return thresholdRunner.run(taskId);
        }
        boolean temp = Boolean.TRUE.equals(taskData.get("isTempTask"));
        boolean retry = Boolean.TRUE.equals(taskData.get("isRetryTask"));
        int trigger = retry ? ReportConstants.TRIGGER_RETRY : temp ? ReportConstants.TRIGGER_MANUAL : ReportConstants.TRIGGER_SCHEDULE;
        if (temp) {
            // 一次性临时任务：执行前即移除，避免次日再次触发
            deTaskExecutor.removeTask(taskId, true);
        }
        return reportRunner.run(taskId, trigger);
    }

    public void init() {
        try {
            reportManage.initSchedules();
        } catch (Exception e) {
            LogUtil.error("init report tasks failed: " + e.getMessage(), e);
        }
        try {
            thresholdManage.initSchedules();
        } catch (Exception e) {
            LogUtil.error("init threshold tasks failed: " + e.getMessage(), e);
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.valueOf(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
