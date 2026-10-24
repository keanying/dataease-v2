package io.dataease.ee.sync.manage;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.sync.task.dto.TaskLogGridRequest;
import io.dataease.api.sync.task.vo.LogResultVO;
import io.dataease.api.sync.task.vo.TaskLogVO;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncTask;
import io.dataease.ee.sync.dao.PerSyncTaskLog;
import io.dataease.ee.sync.dao.PerSyncTaskLogRepository;
import io.dataease.ee.sync.dao.PerSyncTaskRepository;
import io.dataease.ee.sync.dao.QPerSyncTaskLog;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.result.PageResult;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 同步任务日志：持久化、实时内容缓冲、分页、清理
 */
@Component
public class SyncTaskLogManage {

    private final PerSyncTaskLogRepository logRepository;
    private final PerSyncTaskRepository taskRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 运行中日志的实时内容（logId → 内容），结束后以数据库为准
     */
    private final Map<Long, StringBuffer> liveContent = new ConcurrentHashMap<>();

    public SyncTaskLogManage(PerSyncTaskLogRepository logRepository, PerSyncTaskRepository taskRepository, JPAQueryFactory queryFactory) {
        this.logRepository = logRepository;
        this.taskRepository = taskRepository;
        this.queryFactory = queryFactory;
    }

    // ------------------------------------------------------------------ 写入

    public PerSyncTaskLog start(PerSyncTask task, String triggerType) {
        PerSyncTaskLog log = new PerSyncTaskLog();
        log.setId(io.dataease.utils.IDUtils.snowID());
        log.setTaskId(task.getId());
        log.setTaskName(task.getName());
        log.setOid(task.getOid());
        log.setTriggerType(triggerType);
        log.setExecutorStartTime(System.currentTimeMillis());
        log.setStatus(SyncConstants.LOG_RUNNING);
        log.setExecutorAddress(address());
        log.setRowCount(0L);
        log.setLogContent("");
        logRepository.save(log);
        liveContent.put(log.getId(), new StringBuffer());
        return log;
    }

    private static String address() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    public void append(Long logId, String line) {
        StringBuffer buffer = liveContent.computeIfAbsent(logId, id -> new StringBuffer());
        buffer.append(line).append('\n');
    }

    /**
     * 将实时内容落库（运行中定期调用）
     */
    public void flush(Long logId) {
        StringBuffer buffer = liveContent.get(logId);
        if (buffer == null) {
            return;
        }
        PerSyncTaskLog patch = new PerSyncTaskLog();
        patch.setId(logId);
        patch.setLogContent(buffer.toString());
        logRepository.save(patch);
    }

    public void finish(Long logId, String status, String message, long rows) {
        StringBuffer buffer = liveContent.get(logId);
        PerSyncTaskLog patch = new PerSyncTaskLog();
        patch.setId(logId);
        patch.setStatus(status);
        patch.setExecutorEndTime(System.currentTimeMillis());
        patch.setExecutorMsg(StringUtils.left(StringUtils.defaultString(message), 3900));
        patch.setRowCount(rows);
        if (buffer != null) {
            patch.setLogContent(buffer.toString());
        }
        logRepository.save(patch);
        liveContent.remove(logId);
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<TaskLogVO> pager(Long oid, int goPage, int pageSize, TaskLogGridRequest request) {
        Specification<PerSyncTaskLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("oid"), oid));
            if (request != null) {
                Long taskId = SyncDatasourceManage.parseId(request.getTaskId());
                if (StringUtils.isNotBlank(request.getTaskId())) {
                    predicates.add(cb.equal(root.get("taskId"), taskId == null ? -1L : taskId));
                }
                if (StringUtils.isNotBlank(request.getKeyword())) {
                    String keyword = request.getKeyword().trim();
                    Long id = SyncDatasourceManage.parseId(keyword);
                    Predicate byName = cb.like(cb.lower(root.get("taskName")), "%" + keyword.toLowerCase() + "%");
                    predicates.add(id == null ? byName : cb.or(byName, cb.equal(root.get("id"), id), cb.equal(root.get("taskId"), id)));
                }
                if (request.getStatus() != null && !request.getStatus().isEmpty()) {
                    predicates.add(root.get("status").in(request.getStatus()));
                }
                Long[] range = SyncDatasourceManage.timeRange(request.getLastExecuteTime());
                if (range != null) {
                    predicates.add(cb.between(root.get("executorStartTime"), range[0], range[1]));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(Math.max(goPage, 1) - 1, Math.max(pageSize, 1), Sort.by(Sort.Direction.DESC, "executorStartTime"));
        Page<PerSyncTaskLog> page = logRepository.findAll(spec, pageable);
        List<Long> taskIds = page.getContent().stream().map(PerSyncTaskLog::getTaskId).distinct().toList();
        Map<Long, PerSyncTask> tasks = taskRepository.findAllById(taskIds).stream()
                .collect(Collectors.toMap(PerSyncTask::getId, Function.identity()));
        List<TaskLogVO> records = page.getContent().stream().map(log -> toVO(log, tasks.get(log.getTaskId()))).toList();
        return new PageResult<>(records, page.getTotalElements(), pageable);
    }

    public TaskLogVO toVO(PerSyncTaskLog log, PerSyncTask task) {
        TaskLogVO vo = new TaskLogVO();
        vo.setId(String.valueOf(log.getId()));
        vo.setJobId(String.valueOf(log.getTaskId()));
        vo.setJobName(task != null ? task.getName() : log.getTaskName());
        vo.setJobDesc(task != null ? task.getDescription() : null);
        vo.setExecutorStartTime(log.getExecutorStartTime());
        vo.setExecutorEndTime(log.getExecutorEndTime());
        vo.setStatus(log.getStatus());
        vo.setExecutorMsg(log.getExecutorMsg());
        vo.setExecutorAddress(log.getExecutorAddress());
        return vo;
    }

    public PerSyncTaskLog require(Long oid, String logId) {
        Long id = SyncDatasourceManage.parseId(logId);
        PerSyncTaskLog log = id == null ? null : logRepository.findById(id).orElse(null);
        if (log == null || !Objects.equals(log.getOid(), oid)) {
            DEException.throwException(Translator.get("ee_sync_log_not_found"));
        }
        return log;
    }

    public PerSyncTaskLog find(Long logId) {
        return logId == null ? null : logRepository.findById(logId).orElse(null);
    }

    /**
     * 按行增量读取日志内容（行号从 1 开始）
     */
    public LogResultVO detail(Long oid, String logId, int fromLineNum) {
        PerSyncTaskLog log = require(oid, logId);
        int from = Math.max(fromLineNum, 1);
        StringBuffer live = liveContent.get(log.getId());
        boolean finished = live == null && SyncConstants.LOG_FINISHED.contains(log.getStatus());
        String content = live != null ? live.toString() : StringUtils.defaultString(log.getLogContent());
        List<String> lines = content.isEmpty() ? List.of() : List.of(content.split("\n", -1));
        int total = lines.size();
        if (total > 0 && lines.get(total - 1).isEmpty()) {
            total--;
        }
        if (from > total) {
            if (finished) {
                return new LogResultVO(from, from - 1, "", true);
            }
            return new LogResultVO(from, from, SyncConstants.NO_NEW_LOG, false);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= total; i++) {
            sb.append(lines.get(i - 1)).append('\n');
        }
        return new LogResultVO(from, total, sb.toString(), finished);
    }

    // ------------------------------------------------------------------ 删除

    public void delete(Long oid, String logId) {
        PerSyncTaskLog log = require(oid, logId);
        if (SyncConstants.LOG_RUNNING.equals(log.getStatus()) && liveContent.containsKey(log.getId())) {
            DEException.throwException(Translator.get("ee_sync_log_running"));
        }
        logRepository.deleteById(log.getId());
    }

    @Transactional
    public void deleteByTask(Long taskId) {
        QPerSyncTaskLog q = QPerSyncTaskLog.perSyncTaskLog;
        queryFactory.delete(q).where(q.taskId.eq(taskId)).execute();
    }

    /**
     * 清理指定时间之前的日志：clearType 1 天、2 周、3 一个月、4 三个月、5 六个月、6 一年
     */
    @Transactional
    public void clear(Long oid, Long taskId, String clearType) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = switch (StringUtils.defaultIfBlank(clearType, "1")) {
            case "2" -> now.minusWeeks(1);
            case "3" -> now.minusMonths(1);
            case "4" -> now.minusMonths(3);
            case "5" -> now.minusMonths(6);
            case "6" -> now.minusYears(1);
            default -> now.minusDays(1);
        };
        long cutoff = before.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        QPerSyncTaskLog q = QPerSyncTaskLog.perSyncTaskLog;
        com.querydsl.core.BooleanBuilder where = new com.querydsl.core.BooleanBuilder();
        where.and(q.oid.eq(oid)).and(q.executorStartTime.lt(cutoff)).and(q.status.ne(SyncConstants.LOG_RUNNING));
        if (taskId != null) {
            where.and(q.taskId.eq(taskId));
        }
        queryFactory.delete(q).where(where).execute();
    }

    /**
     * 启动时将遗留的运行中日志标记为连接丢失
     */
    @Transactional
    public void markStaleRunning(String message) {
        QPerSyncTaskLog q = QPerSyncTaskLog.perSyncTaskLog;
        queryFactory.update(q).set(q.status, SyncConstants.LOG_CONNECTION_LOST)
                .set(q.executorEndTime, System.currentTimeMillis())
                .set(q.executorMsg, message)
                .where(q.status.eq(SyncConstants.LOG_RUNNING)).execute();
    }

    public boolean isLive(Long logId) {
        return liveContent.containsKey(logId);
    }

    public long count(Long oid) {
        return logRepository.count((root, query, cb) -> cb.equal(root.get("oid"), oid));
    }

    public List<PerSyncTaskLog> since(Long oid, long startMs) {
        return logRepository.findAll((root, query, cb) -> cb.and(cb.equal(root.get("oid"), oid),
                cb.greaterThanOrEqualTo(root.get("executorStartTime"), startMs)));
    }

    public Map<String, Long> statusCount(Long oid) {
        QPerSyncTaskLog q = QPerSyncTaskLog.perSyncTaskLog;
        List<com.querydsl.core.Tuple> rows = queryFactory.select(q.status, q.id.count()).from(q)
                .where(q.oid.eq(oid)).groupBy(q.status).fetch();
        Map<String, Long> result = new java.util.HashMap<>();
        for (com.querydsl.core.Tuple row : rows) {
            result.put(row.get(q.status), row.get(q.id.count()));
        }
        return result;
    }
}
