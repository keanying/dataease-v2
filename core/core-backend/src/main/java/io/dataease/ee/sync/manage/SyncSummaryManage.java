package io.dataease.ee.sync.manage;

import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncTaskLog;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步概览
 */
@Component
public class SyncSummaryManage {

    private final SyncTaskManage taskManage;
    private final SyncDatasourceManage datasourceManage;
    private final SyncTaskLogManage logManage;

    public SyncSummaryManage(SyncTaskManage taskManage, SyncDatasourceManage datasourceManage, SyncTaskLogManage logManage) {
        this.taskManage = taskManage;
        this.datasourceManage = datasourceManage;
        this.logManage = logManage;
    }

    public Map<String, Long> resourceCount(Long oid) {
        Map<String, Long> result = new HashMap<>();
        result.put("jobCount", taskManage.count(oid));
        result.put("datasourceCount", datasourceManage.count(oid));
        result.put("jobLogCount", logManage.count(oid));
        return result;
    }

    /**
     * 过去 7 天（含今天）每日成功/失败次数及整体状态分布
     */
    public Map<String, Object> logChartData(Long oid) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(6);
        long startMs = first.atStartOfDay(zone).toInstant().toEpochMilli();
        DateTimeFormatter label = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<String> dates = new ArrayList<>();
        long[] success = new long[7];
        long[] fail = new long[7];
        long[] running = new long[7];
        for (int i = 0; i < 7; i++) {
            dates.add(first.plusDays(i).format(label));
        }
        for (PerSyncTaskLog log : logManage.since(oid, startMs)) {
            if (log.getExecutorStartTime() == null) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(log.getExecutorStartTime()).atZone(zone).toLocalDate();
            int index = (int) (day.toEpochDay() - first.toEpochDay());
            if (index < 0 || index > 6) {
                continue;
            }
            String status = log.getStatus();
            if (SyncConstants.LOG_SUCCESS.equals(status)) {
                success[index]++;
            } else if (SyncConstants.LOG_RUNNING.equals(status)) {
                running[index]++;
            } else {
                fail[index]++;
            }
        }
        Map<String, Long> counts = logManage.statusCount(oid);
        long successCount = counts.getOrDefault(SyncConstants.LOG_SUCCESS, 0L);
        long runningCount = counts.getOrDefault(SyncConstants.LOG_RUNNING, 0L);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> result = new HashMap<>();
        result.put("executeDateList", dates);
        result.put("executeDateSuccessList", toList(success));
        result.put("executeDateFailList", toList(fail));
        result.put("executeDateRunningList", toList(running));
        result.put("successCount", successCount);
        result.put("failCount", total - successCount - runningCount);
        result.put("runningCount", runningCount);
        return result;
    }

    private static List<Long> toList(long[] values) {
        List<Long> list = new ArrayList<>();
        for (long v : values) {
            list.add(v);
        }
        return list;
    }
}
