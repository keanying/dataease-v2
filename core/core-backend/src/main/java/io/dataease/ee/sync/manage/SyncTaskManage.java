package io.dataease.ee.sync.manage;

import io.dataease.api.sync.task.dto.TaskGridRequest;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncDatasource;
import io.dataease.ee.sync.dao.PerSyncDatasourceRepository;
import io.dataease.ee.sync.dao.PerSyncTask;
import io.dataease.ee.sync.dao.PerSyncTaskLog;
import io.dataease.ee.sync.dao.PerSyncTaskRepository;
import io.dataease.ee.sync.engine.SyncTaskRunner;
import io.dataease.ee.sync.engine.SyncTaskScheduler;
import io.dataease.ee.sync.provider.SyncDialect;
import io.dataease.ee.sync.provider.SyncJdbcSupport;
import io.dataease.ee.sync.provider.SyncProviderResolver;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRole;
import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.model.task.TaskInfoVO;
import io.dataease.extensions.sync.provider.SinkKeyPolicy;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.LogUtil;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.quartz.CronExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 同步任务业务逻辑：增删改查、调度、启停、执行
 */
@Component
public class SyncTaskManage {

    private final PerSyncTaskRepository taskRepository;
    private final PerSyncDatasourceRepository datasourceRepository;
    private final SyncDatasourceManage datasourceManage;
    private final SyncTaskLogManage logManage;
    private final SyncProviderResolver resolver;
    private final SyncTaskScheduler scheduler;
    private final SyncTaskRunner runner;

    public SyncTaskManage(PerSyncTaskRepository taskRepository, PerSyncDatasourceRepository datasourceRepository,
                          SyncDatasourceManage datasourceManage, SyncTaskLogManage logManage, SyncProviderResolver resolver,
                          SyncTaskScheduler scheduler, SyncTaskRunner runner) {
        this.taskRepository = taskRepository;
        this.datasourceRepository = datasourceRepository;
        this.datasourceManage = datasourceManage;
        this.logManage = logManage;
        this.resolver = resolver;
        this.scheduler = scheduler;
        this.runner = runner;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<TaskInfoVO> pager(Long oid, int goPage, int pageSize, TaskGridRequest request) {
        Specification<PerSyncTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("oid"), oid));
            if (request != null) {
                if (StringUtils.isNotBlank(request.getKeyword())) {
                    String keyword = request.getKeyword().trim();
                    Long id = SyncDatasourceManage.parseId(keyword);
                    Predicate byName = cb.like(cb.lower(root.get("name")), "%" + keyword.toLowerCase() + "%");
                    predicates.add(id == null ? byName : cb.or(byName, cb.equal(root.get("id"), id)));
                }
                if (request.getStatus() != null && !request.getStatus().isEmpty()) {
                    predicates.add(root.get("status").in(request.getStatus()));
                }
                if (request.getLogStatus() != null && !request.getLogStatus().isEmpty()) {
                    predicates.add(root.get("lastExecuteStatus").in(request.getLogStatus()));
                }
                Long[] last = SyncDatasourceManage.timeRange(request.getLastExecuteTime());
                if (last != null) {
                    predicates.add(cb.between(root.get("triggerLastTime"), last[0], last[1]));
                }
                Long[] next = SyncDatasourceManage.timeRange(request.getNextExecuteTime());
                if (next != null) {
                    predicates.add(cb.between(root.get("triggerNextTime"), next[0], next[1]));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(Math.max(goPage, 1) - 1, Math.max(pageSize, 1), Sort.by(Sort.Direction.DESC, "createTime"));
        Page<PerSyncTask> page = taskRepository.findAll(spec, pageable);
        Set<Long> dsIds = new HashSet<>();
        page.getContent().forEach(t -> {
            dsIds.add(t.getSourceDsId());
            dsIds.add(t.getTargetDsId());
        });
        dsIds.remove(null);
        Map<Long, PerSyncDatasource> dsMap = datasourceRepository.findAllById(dsIds).stream()
                .collect(Collectors.toMap(PerSyncDatasource::getId, Function.identity()));
        List<TaskInfoVO> records = page.getContent().stream().map(t -> toVO(t, dsMap, false)).toList();
        return new PageResult<>(records, page.getTotalElements(), pageable);
    }

    public TaskInfoVO get(Long oid, String id) {
        PerSyncTask task = require(oid, id);
        Map<Long, PerSyncDatasource> dsMap = new HashMap<>();
        datasourceRepository.findAllById(java.util.Arrays.asList(task.getSourceDsId(), task.getTargetDsId()))
                .forEach(ds -> dsMap.put(ds.getId(), ds));
        return toVO(task, dsMap, true);
    }

    public long count(Long oid) {
        return taskRepository.count((root, query, cb) -> cb.equal(root.get("oid"), oid));
    }

    public PerSyncTask require(Long oid, String id) {
        Long taskId = SyncDatasourceManage.parseId(id);
        PerSyncTask task = taskId == null ? null : taskRepository.findById(taskId).orElse(null);
        if (task == null || !Objects.equals(task.getOid(), oid)) {
            DEException.throwException(Translator.get("ee_sync_task_not_found"));
        }
        return task;
    }

    private TaskInfoVO toVO(PerSyncTask task, Map<Long, PerSyncDatasource> dsMap, boolean detail) {
        TaskInfoVO vo = new TaskInfoVO();
        vo.setId(String.valueOf(task.getId()));
        vo.setName(task.getName());
        vo.setDesc(task.getDescription());
        vo.setStatus(task.getStatus());
        vo.setSchedulerType(task.getSchedulerType());
        vo.setSchedulerConf(task.getSchedulerConf());
        vo.setExecutorTimeout(task.getExecutorTimeout());
        vo.setExecutorFailRetryCount(task.getExecutorFailRetryCount());
        vo.setTriggerLastTime(task.getTriggerLastTime());
        vo.setTriggerNextTime(task.getTriggerNextTime());
        if (SyncConstants.TASK_WAITING.equals(task.getStatus()) && SyncTaskScheduler.scheduled(task)) {
            vo.setTriggerNextTime(SyncTaskScheduler.nextFireTime(task, System.currentTimeMillis()));
        }
        vo.setLastExecuteStatus(task.getLastExecuteStatus());
        vo.setStartTime(task.getStartTime());
        vo.setStopTime(task.getStopTime());
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        vo.setStartTimeString(task.getStartTime() == null ? null : format.format(new Date(task.getStartTime())));
        vo.setStopTimeString(task.getStopTime() == null ? null : format.format(new Date(task.getStopTime())));
        vo.setCreateBy(task.getCreateBy());
        vo.setModifyBy(task.getUpdateBy());
        vo.setOid(task.getOid());
        vo.setIncrementTask(Boolean.TRUE.equals(task.getIncrementSync()));
        vo.setEditable(!Boolean.TRUE.equals(task.getTableCreated()));
        vo.setEditableCheckFailed(false);
        if (task.getCreateBy() != null) {
            V3BaseUser user = V3UserUtil.getUser(task.getCreateBy());
            vo.setUserName(user == null ? null : user.getName());
        }
        Source source = StringUtils.isBlank(task.getSourceConfig()) ? new Source() : JsonUtil.parseObject(task.getSourceConfig(), Source.class);
        Target target = StringUtils.isBlank(task.getTargetConfig()) ? new Target() : JsonUtil.parseObject(task.getTargetConfig(), Target.class);
        source.setDatasourceId(task.getSourceDsId() == null ? null : String.valueOf(task.getSourceDsId()));
        source.setDatasource(dsBrief(dsMap.get(task.getSourceDsId())));
        target.setDatasourceId(task.getTargetDsId() == null ? null : String.valueOf(task.getTargetDsId()));
        target.setDatasource(dsBrief(dsMap.get(task.getTargetDsId())));
        target.setTableName(task.getTargetTable());
        if (StringUtils.isBlank(target.getTargetProperty())) {
            target.setTargetProperty("{}");
        }
        if (!detail) {
            source.setFieldList(null);
            target.setFieldList(null);
        }
        vo.setSource(source);
        vo.setTarget(target);
        if (task.getLastLogId() != null) {
            PerSyncTaskLog log = logManage.find(task.getLastLogId());
            if (log != null) {
                vo.setLogId(String.valueOf(log.getId()));
                vo.setLogStatus(log.getStatus());
                vo.setExecutorStartTime(log.getExecutorStartTime());
                vo.setExecutorEndTime(log.getExecutorEndTime());
                vo.setExecutorMsg(log.getExecutorMsg());
            }
        }
        return vo;
    }

    private DatasourceDTO dsBrief(PerSyncDatasource ds) {
        DatasourceDTO dto = new DatasourceDTO();
        if (ds == null) {
            dto.setName("-");
            return dto;
        }
        dto.setId(String.valueOf(ds.getId()));
        dto.setName(ds.getName());
        dto.setType(ds.getType());
        dto.setDatasourceRole(ds.getDatasourceRole());
        dto.setStatus(ds.getStatus());
        return dto;
    }

    // ------------------------------------------------------------------ 新增 / 修改

    public void add(Long oid, TaskInfoVO info) {
        PerSyncTask task = new PerSyncTask();
        task.setId(IDUtils.snowID());
        task.setOid(oid);
        applyBasic(task, info);
        applyStructure(oid, task, info, true);
        task.setTableCreated(false);
        task.setCreateBy(V3UserUtil.getUid());
        task.setCreateTime(System.currentTimeMillis());
        task.setUpdateBy(V3UserUtil.getUid());
        task.setUpdateTime(task.getCreateTime());
        task.setStatus(SyncTaskScheduler.scheduled(task) ? SyncConstants.TASK_WAITING : SyncConstants.TASK_UNEXECUTED);
        taskRepository.save(task);
        reschedule(task);
        if (SyncConstants.SCHEDULER_NONE.equals(task.getSchedulerType())) {
            // 立即同步
            runner.submit(task.getId());
        }
    }

    public void update(Long oid, TaskInfoVO info) {
        PerSyncTask task = require(oid, info.getId());
        if (runner.isRunning(task.getId())) {
            DEException.throwException(Translator.get("ee_sync_task_running"));
        }
        String oldType = task.getSchedulerType();
        applyBasic(task, info);
        applyStructure(oid, task, info, false);
        task.setUpdateBy(V3UserUtil.getUid());
        task.setUpdateTime(System.currentTimeMillis());
        if (!SyncConstants.TASK_SUSPEND.equals(task.getStatus())) {
            if (SyncTaskScheduler.scheduled(task)) {
                task.setStatus(SyncConstants.TASK_WAITING);
            } else if (!Objects.equals(oldType, task.getSchedulerType())) {
                task.setStatus(task.getLastLogId() == null ? SyncConstants.TASK_UNEXECUTED : SyncConstants.TASK_DONE);
            }
        }
        taskRepository.save(task);
        reschedule(task);
    }

    private void applyBasic(PerSyncTask task, TaskInfoVO info) {
        String name = StringUtils.trimToEmpty(info.getName());
        if (name.isEmpty() || name.length() > 255) {
            DEException.throwException(Translator.get("ee_sync_task_name_invalid"));
        }
        task.setName(name);
        task.setDescription(StringUtils.left(StringUtils.defaultString(info.getDesc()), 512));
        String type = StringUtils.defaultIfBlank(info.getSchedulerType(), SyncConstants.SCHEDULER_NONE).toUpperCase();
        String conf = StringUtils.trimToEmpty(info.getSchedulerConf());
        switch (type) {
            case SyncConstants.SCHEDULER_CRON -> {
                if (!CronExpression.isValidExpression(conf)) {
                    DEException.throwException(Translator.get("ee_sync_task_cron_invalid", conf));
                }
            }
            case SyncConstants.SCHEDULER_FIX_RATE -> {
                if (SyncTaskScheduler.periodMillis(conf) <= 0) {
                    DEException.throwException(Translator.get("ee_sync_task_rate_invalid", conf));
                }
            }
            case SyncConstants.SCHEDULER_NONE -> conf = "";
            default -> DEException.throwException(Translator.get("ee_sync_task_scheduler_invalid", type));
        }
        task.setSchedulerType(type);
        task.setSchedulerConf(conf);
        long timeout = info.getExecutorTimeout() == null ? 0 : info.getExecutorTimeout();
        long retry = info.getExecutorFailRetryCount() == null ? 0 : info.getExecutorFailRetryCount();
        if (timeout < 0 || timeout > 86400 || retry < 0 || retry > 10) {
            DEException.throwException(Translator.get("ee_sync_task_executor_invalid"));
        }
        task.setExecutorTimeout(timeout);
        task.setExecutorFailRetryCount(retry);
        Long start = parseTime(info.getStartTimeString(), info.getStartTime());
        Long stop = parseTime(info.getStopTimeString(), info.getStopTime());
        if (SyncConstants.SCHEDULER_NONE.equals(type)) {
            start = null;
            stop = null;
        } else if (start == null) {
            start = System.currentTimeMillis();
        }
        if (start != null && stop != null && stop <= start) {
            DEException.throwException(Translator.get("ee_sync_task_time_invalid"));
        }
        task.setStartTime(start);
        task.setStopTime(stop);
    }

    private static Long parseTime(String text, Long fallback) {
        if (StringUtils.isBlank(text)) {
            return fallback != null && fallback > 0 ? fallback : null;
        }
        String value = text.trim().replace('T', ' ');
        if (value.matches("\\d{10,}")) {
            return Long.parseLong(value);
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern);
                format.setLenient(false);
                return format.parse(value.length() > pattern.length() ? value.substring(0, pattern.length()) : value).getTime();
            } catch (Exception ignored) {
            }
        }
        DEException.throwException(Translator.get("ee_sync_task_time_invalid"));
        return null;
    }

    /**
     * 源/目标结构；目标表已创建（不可编辑）时保留原结构，仅接受同步策略类参数
     */
    private void applyStructure(Long oid, PerSyncTask task, TaskInfoVO info, boolean create) {
        Source source = info.getSource();
        Target target = info.getTarget();
        if (source == null || target == null) {
            DEException.throwException(Translator.get("ee_sync_task_config_invalid"));
        }
        boolean locked = !create && Boolean.TRUE.equals(task.getTableCreated());
        if (locked) {
            Source oldSource = JsonUtil.parseObject(task.getSourceConfig(), Source.class);
            Target oldTarget = JsonUtil.parseObject(task.getTargetConfig(), Target.class);
            oldTarget.setIncrementSync(target.getIncrementSync());
            oldTarget.setIncrementField(target.getIncrementField());
            oldTarget.setIncrementFieldType(target.getIncrementFieldType());
            oldTarget.setIncrementOffset(target.getIncrementOffset());
            oldTarget.setIncrementOffsetUnit(target.getIncrementOffsetUnit());
            oldTarget.setFaultToleranceRate(target.getFaultToleranceRate());
            if (target.getTargetProperty() != null) {
                oldTarget.setTargetProperty(target.getTargetProperty());
            }
            source = oldSource;
            target = oldTarget;
            target.setTableName(task.getTargetTable());
            source.setDatasourceId(String.valueOf(task.getSourceDsId()));
            target.setDatasourceId(String.valueOf(task.getTargetDsId()));
        }
        PerSyncDatasource sourceDs = datasourceManage.require(oid, source.getDatasourceId());
        PerSyncDatasource targetDs = datasourceManage.require(oid, target.getDatasourceId());
        if (!Objects.equals(sourceDs.getDatasourceRole(), DatasourceRole.SOURCE) || !Objects.equals(targetDs.getDatasourceRole(), DatasourceRole.TARGET)) {
            DEException.throwException(Translator.get("ee_sync_task_ds_role_invalid"));
        }
        source.setType(sourceDs.getType());
        target.setType(targetDs.getType());
        if (SyncConstants.EXTRACT_SQL.equals(source.getTableExtract())) {
            io.dataease.ee.sync.provider.JdbcSyncProvider.checkQuery(source.getQuery());
            source.setTables(null);
        } else {
            source.setTableExtract(SyncConstants.EXTRACT_TABLE);
            if (StringUtils.isBlank(source.getTables())) {
                DEException.throwException(Translator.get("ee_sync_task_source_table_empty"));
            }
            source.setQuery(null);
        }
        target.setTableName(StringUtils.trimToEmpty(target.getTableName()));
        if (target.getFieldList() != null) {
            target.setFieldList(new ArrayList<>(target.getFieldList().stream().filter(Objects::nonNull).toList()));
            for (TableFieldDTO field : target.getFieldList()) {
                field.setFieldName(StringUtils.trimToEmpty(field.getFieldName()));
                if (StringUtils.isBlank(field.getFieldSource())) {
                    field.setFieldSource(field.getFieldName());
                }
            }
        }
        if (source.getFieldList() != null && !source.getFieldList().isEmpty() && target.getFieldList() != null) {
            Set<String> sourceNames = new HashSet<>();
            source.getFieldList().forEach(f -> {
                sourceNames.add(f.getFieldName());
                sourceNames.add(f.getFieldSource());
            });
            for (TableFieldDTO field : target.getFieldList()) {
                if (!sourceNames.contains(field.getFieldSource())) {
                    DEException.throwException(Translator.get("ee_sync_task_source_field_missing", field.getFieldSource()));
                }
            }
        }
        SinkProvider sink = resolver.sink(resolver.provider(targetDs.getType(), targetDs.getDatasourceRole()));
        resolver.source(resolver.provider(sourceDs.getType(), sourceDs.getDatasourceRole()));
        sink.validateTarget(target);
        if (sink.keyPolicy(target) == SinkKeyPolicy.REQUIRED && target.getFieldList().stream().noneMatch(TableFieldDTO::isFieldPk)) {
            DEException.throwException(Translator.get("ee_sync_task_key_required"));
        }
        boolean increment = SyncConstants.SWITCH_ON.equalsIgnoreCase(target.getIncrementSync());
        if (increment) {
            String field = target.getIncrementField();
            TableFieldDTO incField = target.getFieldList().stream().filter(f -> StringUtils.equals(f.getFieldName(), field)).findFirst().orElse(null);
            if (incField == null) {
                DEException.throwException(Translator.get("ee_sync_task_increment_field_invalid"));
            }
            target.setIncrementFieldType(incField.getFieldType());
            source.setIncrementField(incField.getFieldSource());
        } else {
            target.setIncrementSync("off");
        }
        target.setFieldMappingVersion(Target.CURRENT_FIELD_MAPPING_VERSION);

        if (!locked) {
            // 目标表名唯一：同一目标库不允许多个任务写同一张表；新建（或改表名）时目标表不能已存在
            Long targetDsId = targetDs.getId();
            String tableName = target.getTableName();
            boolean conflict = taskRepository.findAll((root, query, cb) -> cb.and(cb.equal(root.get("targetDsId"), targetDsId),
                    cb.equal(root.get("targetTable"), tableName))).stream().anyMatch(t -> !Objects.equals(t.getId(), task.getId()));
            if (conflict) {
                DEException.throwException(Translator.get("ee_sync_task_table_used", tableName));
            }
            if (create || !StringUtils.equals(tableName, task.getTargetTable()) || !Objects.equals(targetDsId, task.getTargetDsId())) {
                if (targetTableExists(targetDs, tableName)) {
                    DEException.throwException(Translator.get("ee_sync_task_table_exists", tableName));
                }
            }
        }

        Source storedSource = new Source(source);
        storedSource.setDatasource(null);
        Target storedTarget = new Target(target);
        storedTarget.setDatasource(null);
        task.setSourceDsId(sourceDs.getId());
        task.setTargetDsId(targetDs.getId());
        task.setTargetTable(target.getTableName());
        task.setIncrementSync(increment);
        task.setSourceConfig(JsonUtil.toJSONString(storedSource).toString());
        task.setTargetConfig(JsonUtil.toJSONString(storedTarget).toString());
    }

    private boolean targetTableExists(PerSyncDatasource targetDs, String table) {
        SyncDialect dialect = resolver.dialect(targetDs.getType(), targetDs.getDatasourceRole(), datasourceManage.internalRequest(targetDs));
        DatasourceConfiguration cfg = dialect.configuration(targetDs.getConfiguration());
        try (Connection con = dialect.open(cfg)) {
            return dialect.tableExists(con, cfg, table);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_sync_connect_failed", SyncJdbcSupport.rootMessage(e)));
        }
        return false;
    }

    private void reschedule(PerSyncTask task) {
        if (SyncConstants.TASK_SUSPEND.equals(task.getStatus()) || !SyncTaskScheduler.scheduled(task)) {
            scheduler.unschedule(task.getId());
            updateNext(task, null, null);
            return;
        }
        Long next = scheduler.schedule(task);
        updateNext(task, next, next == null ? SyncConstants.TASK_DONE : null);
    }

    private void updateNext(PerSyncTask task, Long next, String status) {
        task.setTriggerNextTime(next);
        String newStatus = status != null && !runner.isRunning(task.getId()) ? status : null;
        runner.updateSchedule(task.getId(), next, newStatus);
    }

    // ------------------------------------------------------------------ 删除 / 启停 / 执行

    public void remove(Long oid, String id) {
        PerSyncTask task = require(oid, id);
        if (runner.isRunning(task.getId())) {
            DEException.throwException(Translator.get("ee_sync_task_running"));
        }
        scheduler.unschedule(task.getId());
        logManage.deleteByTask(task.getId());
        taskRepository.deleteById(task.getId());
    }

    public void batchRemove(Long oid, List<String> ids) {
        if (ids == null) {
            return;
        }
        List<PerSyncTask> tasks = ids.stream().map(id -> require(oid, id)).toList();
        for (PerSyncTask task : tasks) {
            if (runner.isRunning(task.getId())) {
                DEException.throwException(Translator.get("ee_sync_task_running"));
            }
        }
        for (PerSyncTask task : tasks) {
            remove(oid, String.valueOf(task.getId()));
        }
    }

    public void start(Long oid, String id) {
        PerSyncTask task = require(oid, id);
        if (!SyncTaskScheduler.scheduled(task)) {
            DEException.throwException(Translator.get("ee_sync_task_not_scheduled"));
        }
        PerSyncTask patch = new PerSyncTask();
        patch.setId(task.getId());
        patch.setStatus(runner.isRunning(task.getId()) ? SyncConstants.TASK_RUNNING : SyncConstants.TASK_WAITING);
        taskRepository.save(patch);
        task.setStatus(patch.getStatus());
        reschedule(task);
    }

    public void stop(Long oid, String id) {
        PerSyncTask task = require(oid, id);
        scheduler.unschedule(task.getId());
        runner.updateSchedule(task.getId(), null, SyncConstants.TASK_SUSPEND);
    }

    public void execute(Long oid, String id) {
        PerSyncTask task = require(oid, id);
        runner.submit(task.getId());
    }

    public SinkKeyPolicy keyPolicy(Long oid, Target target) {
        if (target == null || StringUtils.isBlank(target.getDatasourceId())) {
            return SinkKeyPolicy.OPTIONAL;
        }
        PerSyncDatasource ds = datasourceManage.require(oid, target.getDatasourceId());
        return resolver.sink(resolver.provider(ds.getType(), ds.getDatasourceRole())).keyPolicy(target);
    }

    /**
     * 启动时恢复：遗留运行中的日志标记为连接丢失，重新注册调度
     */
    public void init() {
        try {
            logManage.markStaleRunning(Translator.get("ee_sync_log_restart"));
        } catch (Exception e) {
            LogUtil.error("mark stale sync logs failed: " + e.getMessage(), e);
        }
        for (PerSyncTask task : taskRepository.findAll()) {
            try {
                if (SyncConstants.TASK_RUNNING.equals(task.getStatus())) {
                    task.setStatus(SyncTaskScheduler.scheduled(task) ? SyncConstants.TASK_WAITING : SyncConstants.TASK_DONE);
                    PerSyncTask patch = new PerSyncTask();
                    patch.setId(task.getId());
                    patch.setStatus(task.getStatus());
                    if (SyncConstants.LOG_RUNNING.equals(task.getLastExecuteStatus())) {
                        patch.setLastExecuteStatus(SyncConstants.LOG_CONNECTION_LOST);
                    }
                    taskRepository.save(patch);
                }
                if (SyncTaskScheduler.scheduled(task) && SyncConstants.TASK_WAITING.equals(task.getStatus())) {
                    reschedule(task);
                }
            } catch (Exception e) {
                LogUtil.error("restore sync task schedule failed: " + task.getId() + ", " + e.getMessage(), e);
            }
        }
    }
}
