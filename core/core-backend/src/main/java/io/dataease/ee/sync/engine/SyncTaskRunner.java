package io.dataease.ee.sync.engine;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncDatasource;
import io.dataease.ee.sync.dao.PerSyncDatasourceRepository;
import io.dataease.ee.sync.dao.PerSyncTask;
import io.dataease.ee.sync.dao.PerSyncTaskLog;
import io.dataease.ee.sync.dao.PerSyncTaskRepository;
import io.dataease.ee.sync.dao.QPerSyncTask;
import io.dataease.ee.sync.manage.SyncTaskLogManage;
import io.dataease.ee.sync.provider.SyncDialect;
import io.dataease.ee.sync.provider.SyncJdbcSupport;
import io.dataease.ee.sync.provider.SyncProviderResolver;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步任务执行：日志、状态流转、重试、超时、终止
 */
@Component
public class SyncTaskRunner {

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";

    private final PerSyncTaskRepository taskRepository;
    private final PerSyncDatasourceRepository datasourceRepository;
    private final SyncTaskLogManage logManage;
    private final SyncProviderResolver resolver;
    private final SyncTaskScheduler scheduler;
    private final JPAQueryFactory queryFactory;
    private final TransactionTemplate transactionTemplate;

    /**
     * 本节点运行中的任务
     */
    private final Map<Long, SyncJobContext> running = new ConcurrentHashMap<>();

    private final ExecutorService manualExecutor;

    public SyncTaskRunner(PerSyncTaskRepository taskRepository, PerSyncDatasourceRepository datasourceRepository,
                          SyncTaskLogManage logManage, SyncProviderResolver resolver, SyncTaskScheduler scheduler,
                          JPAQueryFactory queryFactory, PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.taskRepository = taskRepository;
        this.datasourceRepository = datasourceRepository;
        this.logManage = logManage;
        this.resolver = resolver;
        this.scheduler = scheduler;
        this.queryFactory = queryFactory;
        AtomicInteger seq = new AtomicInteger();
        this.manualExecutor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "de-sync-task-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        running.values().forEach(SyncJobContext::cancel);
        manualExecutor.shutdownNow();
    }

    public boolean isRunning(Long taskId) {
        return running.containsKey(taskId);
    }

    public SyncJobContext runningByLog(Long logId) {
        return running.values().stream().filter(c -> logId.equals(c.getLogId())).findFirst().orElse(null);
    }

    /**
     * 异步执行一次（手动触发/立即同步）
     */
    public void submit(Long taskId) {
        if (running.containsKey(taskId)) {
            DEException.throwException(Translator.get("ee_sync_task_running"));
        }
        java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        manualExecutor.submit(() -> run(taskId, TRIGGER_MANUAL, locale));
    }

    /**
     * 同步执行一次，返回是否成功
     */
    public boolean run(Long taskId, String triggerType) {
        return run(taskId, triggerType, null);
    }

    /**
     * 同步执行一次；locale 为日志语言（定时触发无请求上下文时默认简体中文）
     */
    public boolean run(Long taskId, String triggerType, java.util.Locale locale) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale == null ? java.util.Locale.SIMPLIFIED_CHINESE : locale);
        try {
            return doRun(taskId, triggerType);
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    private boolean doRun(Long taskId, String triggerType) {
        PerSyncTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            scheduler.unschedule(taskId);
            return false;
        }
        if (TRIGGER_SCHEDULE.equals(triggerType) && SyncConstants.TASK_SUSPEND.equals(task.getStatus())) {
            return false;
        }
        PerSyncTaskLog log;
        SyncJobContext context;
        synchronized (running) {
            if (running.containsKey(taskId)) {
                LogUtil.info("sync task is running, skip: " + taskId);
                return false;
            }
            log = logManage.start(task, triggerType);
            context = new SyncJobContext(taskId, log.getId());
            running.put(taskId, context);
        }
        long startedAt = System.currentTimeMillis();
        PerSyncTask patch = new PerSyncTask();
        patch.setId(taskId);
        patch.setStatus(SyncConstants.TASK_RUNNING);
        patch.setLastLogId(log.getId());
        patch.setLastExecuteStatus(SyncConstants.LOG_RUNNING);
        patch.setTriggerLastTime(startedAt);
        taskRepository.save(patch);

        String status = SyncConstants.LOG_FAIL;
        String message = null;
        long rows = 0;
        boolean created = false;
        V3UserUtil.setContext(task.getCreateBy(), task.getOid());
        try {
            Source source = JsonUtil.parseObject(task.getSourceConfig(), Source.class);
            Target target = JsonUtil.parseObject(task.getTargetConfig(), Target.class);
            PerSyncDatasource sourceDs = datasourceRepository.findById(task.getSourceDsId()).orElse(null);
            PerSyncDatasource targetDs = datasourceRepository.findById(task.getTargetDsId()).orElse(null);
            if (sourceDs == null || targetDs == null || source == null || target == null) {
                throw new IllegalStateException(Translator.get("ee_sync_ds_not_found"));
            }
            target.setTableName(task.getTargetTable());
            SyncDialect sourceDialect = resolver.dialect(sourceDs.getType(), sourceDs.getDatasourceRole(), request(sourceDs));
            SyncDialect targetDialect = resolver.dialect(targetDs.getType(), targetDs.getDatasourceRole(), request(targetDs));
            SinkProvider sink = resolver.sink(resolver.provider(targetDs.getType(), targetDs.getDatasourceRole()));
            sink.validateTargetForExecution(target);
            DatasourceConfiguration sourceCfg = sourceDialect.configuration(sourceDs.getConfiguration());
            DatasourceConfiguration targetCfg = targetDialect.configuration(targetDs.getConfiguration());

            log(context, Translator.get("ee_sync_log_start", task.getName(), triggerType));
            log(context, Translator.get("ee_sync_log_source", sourceDs.getName(), sourceDs.getType(),
                    "1".equals(source.getTableExtract()) ? "SQL" : source.getTables()));
            log(context, Translator.get("ee_sync_log_target", targetDs.getName(), targetDs.getType(), target.getTableName(),
                    SyncConstants.SWITCH_ON.equalsIgnoreCase(target.getIncrementSync()) ? "increment" : "full"));

            long timeout = task.getExecutorTimeout() == null ? 0 : task.getExecutorTimeout();
            long deadline = timeout > 0 ? startedAt + timeout * 1000L : 0;
            int attempts = 1 + (int) Math.max(0, Math.min(10, task.getExecutorFailRetryCount() == null ? 0 : task.getExecutorFailRetryCount()));
            for (int attempt = 1; ; attempt++) {
                try {
                    SyncCopier copier = new SyncCopier(sourceDialect, sourceCfg, targetDialect, targetCfg, source, target,
                            context, line -> log(context, line), deadline);
                    SyncCopier.Result result = copier.run();
                    rows = result.rows();
                    created = result.tableCreated();
                    status = SyncConstants.LOG_SUCCESS;
                    message = Translator.get("ee_sync_log_success", String.valueOf(rows));
                    break;
                } catch (SyncCopier.TerminatedException | SyncCopier.TimeoutException e) {
                    throw e;
                } catch (Exception e) {
                    String error = SyncJdbcSupport.rootMessage(e);
                    log(context, "ERROR " + error);
                    LogUtil.error("sync task " + taskId + " failed: " + error, e);
                    if (context.isCancelled()) {
                        throw new SyncCopier.TerminatedException();
                    }
                    if (attempt >= attempts || (deadline > 0 && System.currentTimeMillis() > deadline)) {
                        message = error;
                        break;
                    }
                    log(context, Translator.get("ee_sync_log_retry", String.valueOf(attempt), String.valueOf(attempts - 1)));
                }
            }
        } catch (SyncCopier.TerminatedException e) {
            status = SyncConstants.LOG_TERMINATION;
            message = Translator.get("ee_sync_log_terminated");
            log(context, message);
        } catch (SyncCopier.TimeoutException e) {
            status = SyncConstants.LOG_FAIL;
            message = e.getMessage();
            log(context, "ERROR " + message);
        } catch (Exception e) {
            status = SyncConstants.LOG_FAIL;
            message = SyncJdbcSupport.rootMessage(e);
            log(context, "ERROR " + message);
            LogUtil.error("sync task " + taskId + " failed: " + message, e);
        } finally {
            try {
                log(context, Translator.get("ee_sync_log_end", status, String.valueOf(System.currentTimeMillis() - startedAt)));
                logManage.finish(log.getId(), status, message, rows);
                finishTask(taskId, log.getId(), status, created);
            } catch (Exception e) {
                LogUtil.error("finish sync task failed: " + e.getMessage(), e);
            } finally {
                running.remove(taskId);
                V3UserUtil.clear();
            }
        }
        return SyncConstants.LOG_SUCCESS.equals(status);
    }

    private io.dataease.extensions.sync.model.datasource.DatasourceRequest request(PerSyncDatasource ds) {
        io.dataease.extensions.sync.model.datasource.DatasourceRequest request = new io.dataease.extensions.sync.model.datasource.DatasourceRequest();
        request.setId(String.valueOf(ds.getId()));
        request.setName(ds.getName());
        request.setType(ds.getType());
        request.setDatasourceRole(ds.getDatasourceRole());
        request.setConfiguration(ds.getConfiguration());
        return request;
    }

    private void log(SyncJobContext context, String line) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        logManage.append(context.getLogId(), "[" + time + "] " + line);
        try {
            logManage.flush(context.getLogId());
        } catch (Exception e) {
            LogUtil.warn("flush sync log failed: " + e.getMessage());
        }
    }

    /**
     * 执行结束后更新任务状态与下次执行时间
     */
    public void finishTask(Long taskId, Long logId, String logStatus, boolean tableCreated) {
        PerSyncTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getLastLogId() != null && !task.getLastLogId().equals(logId)) {
            return;
        }
        String status;
        Long next = null;
        if (SyncConstants.TASK_SUSPEND.equals(task.getStatus())) {
            status = SyncConstants.TASK_SUSPEND;
        } else if (SyncTaskScheduler.scheduled(task)) {
            next = SyncTaskScheduler.nextFireTime(task, System.currentTimeMillis());
            status = next == null ? SyncConstants.TASK_DONE : SyncConstants.TASK_WAITING;
        } else {
            status = SyncConstants.TASK_DONE;
        }
        QPerSyncTask q = QPerSyncTask.perSyncTask;
        var update = queryFactory.update(q)
                .set(q.status, status)
                .set(q.lastExecuteStatus, logStatus)
                .set(q.lastLogId, logId);
        if (next == null) {
            update.setNull(q.triggerNextTime);
        } else {
            update.set(q.triggerNextTime, next);
        }
        if (tableCreated) {
            update.set(q.tableCreated, true);
        }
        transactionTemplate.executeWithoutResult(tx -> update.where(q.id.eq(taskId)).execute());
        if (doneAndScheduled(task, status)) {
            scheduler.unschedule(taskId);
        }
    }

    private static boolean doneAndScheduled(PerSyncTask task, String status) {
        return SyncConstants.TASK_DONE.equals(status) && SyncTaskScheduler.scheduled(task);
    }

    /**
     * 更新下次执行时间（null 清空）与状态（null 不变）
     */
    public void updateSchedule(Long taskId, Long next, String status) {
        QPerSyncTask q = QPerSyncTask.perSyncTask;
        var update = queryFactory.update(q);
        if (next == null) {
            update.setNull(q.triggerNextTime);
        } else {
            update.set(q.triggerNextTime, next);
        }
        if (status != null) {
            update.set(q.status, status);
        }
        transactionTemplate.executeWithoutResult(tx -> update.where(q.id.eq(taskId)).execute());
    }

    /**
     * 终止本节点运行中的执行
     */
    public boolean terminate(Long logId) {
        SyncJobContext context = runningByLog(logId);
        if (context == null) {
            return false;
        }
        context.cancel();
        return true;
    }

    public void cancelTask(Long taskId) {
        SyncJobContext context = running.get(taskId);
        if (context != null) {
            context.cancel();
        }
    }

}
