package io.dataease.ee.sync.engine;

import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncTask;
import io.dataease.job.schedule.DeXpackDataSyncTaskExecutor;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.quartz.CronExpression;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 同步任务调度：复用核心 Quartz 执行器 {@link DeXpackDataSyncTaskExecutor}
 */
@Component
public class SyncTaskScheduler {

    private final DeXpackDataSyncTaskExecutor executor;

    public SyncTaskScheduler(DeXpackDataSyncTaskExecutor executor) {
        this.executor = executor;
    }

    public static boolean scheduled(PerSyncTask task) {
        return SyncConstants.SCHEDULER_CRON.equals(task.getSchedulerType()) || SyncConstants.SCHEDULER_FIX_RATE.equals(task.getSchedulerType());
    }

    /**
     * 注册（或更新）调度；返回下次执行时间，null 表示已无后续执行
     */
    public Long schedule(PerSyncTask task) {
        unschedule(task.getId());
        if (!scheduled(task)) {
            return null;
        }
        Long next = nextFireTime(task, System.currentTimeMillis());
        if (next == null) {
            return null;
        }
        String taskId = String.valueOf(task.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("oid", task.getOid());
        if (SyncConstants.SCHEDULER_CRON.equals(task.getSchedulerType())) {
            // 起始时间不早于当前，避免 Quartz 把过去的触发点当作错过触发立即补偿执行
            long start = Math.max(task.getStartTime() == null ? 0 : task.getStartTime(), System.currentTimeMillis());
            executor.addOrUpdateSyncTask(taskId, task.getSchedulerConf(), start, task.getStopTime(), data);
        } else {
            executor.addOrUpdateSyncSimpleJob(taskId, task.getSchedulerConf(), next, task.getStopTime(), data);
        }
        return next;
    }

    public void unschedule(Long taskId) {
        try {
            executor.removeSyncTask(String.valueOf(taskId), false);
            executor.removeSyncTask(String.valueOf(taskId), true);
        } catch (Exception e) {
            LogUtil.warn("remove sync schedule failed: " + e.getMessage());
        }
    }

    /**
     * 计算 after 之后的下次执行时间（遵守开始/结束时间）
     */
    public static Long nextFireTime(PerSyncTask task, long after) {
        long start = task.getStartTime() == null ? 0 : task.getStartTime();
        Long stop = task.getStopTime();
        Long next = null;
        if (SyncConstants.SCHEDULER_CRON.equals(task.getSchedulerType())) {
            try {
                CronExpression cron = new CronExpression(task.getSchedulerConf());
                Date date = cron.getNextValidTimeAfter(new Date(Math.max(after, start - 1)));
                next = date == null ? null : date.getTime();
            } catch (Exception e) {
                return null;
            }
        } else if (SyncConstants.SCHEDULER_FIX_RATE.equals(task.getSchedulerType())) {
            long period = periodMillis(task.getSchedulerConf());
            if (period <= 0) {
                return null;
            }
            if (after < start) {
                next = start;
            } else {
                long n = (after - start) / period + 1;
                next = start + n * period;
            }
        }
        if (next != null && stop != null && stop > 0 && next > stop) {
            return null;
        }
        return next;
    }

    public static long periodMillis(String conf) {
        if (StringUtils.isBlank(conf) || !conf.trim().matches("^\\d+[smhd]$")) {
            return -1;
        }
        String value = conf.trim();
        long number = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> number * 1000L;
            case 'm' -> number * 60_000L;
            case 'h' -> number * 3_600_000L;
            default -> number * 86_400_000L;
        };
    }
}
