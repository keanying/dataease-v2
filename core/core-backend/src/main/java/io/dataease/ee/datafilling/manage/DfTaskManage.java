package io.dataease.ee.datafilling.manage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.report.dto.ReportInstanceMsgRequest;
import io.dataease.api.report.vo.ReportGridVO;
import io.dataease.api.xpack.dataFilling.dto.*;
import io.dataease.commons.utils.CronUtils;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.datafilling.DfConstants;
import io.dataease.ee.datafilling.dao.entity.*;
import io.dataease.ee.datafilling.dao.repo.DfSubTaskRepository;
import io.dataease.ee.datafilling.dao.repo.DfTaskRepository;
import io.dataease.ee.datafilling.dao.repo.DfUserTaskItemRepository;
import io.dataease.ee.datafilling.dao.repo.DfUserTaskRepository;
import io.dataease.ee.datafilling.jdbc.DfJdbcSupport;
import io.dataease.ee.datafilling.jdbc.DfSession;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtFormSettings;
import io.dataease.i18n.Translator;
import io.dataease.job.schedule.DeDataFillingTaskExecutor;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.websocket.WsMessage;
import io.dataease.websocket.WsService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 数据填报任务：下发计划、子任务、用户填报任务
 */
@Component
public class DfTaskManage {

    private final DfTaskRepository taskRepository;

    private final DfSubTaskRepository subTaskRepository;

    private final DfUserTaskRepository userTaskRepository;

    private final DfUserTaskItemRepository itemRepository;

    private final JPAQueryFactory queryFactory;

    private final DfAccess access;

    private final DfUsers users;

    private final DfJdbcSupport jdbcSupport;

    private final DfDataManage dataManage;

    private final DfExcelManage excelManage;

    private final DeDataFillingTaskExecutor executor;

    private final WsService wsService;

    private final TransactionTemplate transactionTemplate;

    public DfTaskManage(DfTaskRepository taskRepository, DfSubTaskRepository subTaskRepository, DfUserTaskRepository userTaskRepository,
                        DfUserTaskItemRepository itemRepository, JPAQueryFactory queryFactory, DfAccess access, DfUsers users,
                        DfJdbcSupport jdbcSupport, DfDataManage dataManage, DfExcelManage excelManage,
                        @Lazy DeDataFillingTaskExecutor executor, @Lazy WsService wsService, TransactionTemplate transactionTemplate) {
        this.taskRepository = taskRepository;
        this.subTaskRepository = subTaskRepository;
        this.userTaskRepository = userTaskRepository;
        this.itemRepository = itemRepository;
        this.queryFactory = queryFactory;
        this.access = access;
        this.users = users;
        this.jdbcSupport = jdbcSupport;
        this.dataManage = dataManage;
        this.excelManage = excelManage;
        this.executor = executor;
        this.wsService = wsService;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------ task CRUD

    private DfTask task(Long id) {
        DfTask task = id == null ? null : taskRepository.findById(id).orElse(null);
        if (task == null) {
            DEException.throwException(Translator.get("ee_df_task_not_exists"));
        }
        return task;
    }

    public TaskInfoVO info(Long taskId) {
        DfTask task = task(taskId);
        access.require(task.getFormId(), EeConstants.WEIGHT_READ);
        TaskInfoVO vo = new TaskInfoVO();
        vo.setId(task.getId());
        vo.setFormId(task.getFormId());
        vo.setName(task.getName());
        vo.setReciFlagList(Optional.ofNullable(DfJson.read(task.getReciFlagList(), new TypeReference<List<Integer>>() {
        })).orElse(new ArrayList<>(List.of(0))));
        vo.setMsgType(task.getMsgType());
        vo.setMsgTitle(task.getMsgTitle());
        vo.setMsgContent(task.getMsgContent());
        vo.setUidList(longs(task.getUidList()));
        vo.setRidList(longs(task.getRidList()));
        vo.setFillType(task.getFillType());
        vo.setFitType(task.getFitType());
        vo.setFitColumn(task.getFitColumn());
        vo.setRateType(task.getRateType());
        vo.setOneTimeType(task.getOneTimeType());
        vo.setRateVal(task.getRateVal());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setPublishRangeTime(task.getPublishRangeTime());
        vo.setPublishRangeTimeType(task.getPublishRangeTimeType());
        vo.setFormExtSetting(task.getFormExtSetting());
        vo.setFormFilterSetting(task.getFormFilterSetting());
        vo.setCreateBy(task.getCreateBy());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateBy(task.getUpdateBy());
        vo.setUpdateTime(task.getUpdateTime());
        Map<Long, String> names = users.names(Arrays.asList(task.getCreateBy(), task.getUpdateBy()));
        vo.setCreator(names.get(task.getCreateBy()));
        vo.setUpdater(names.get(task.getUpdateBy()));
        long now = System.currentTimeMillis();
        vo.setStatus(status(task, now));
        vo.setLastExecStatus(task.getLastExecStatus());
        vo.setLastExecTime(task.getLastExecTime());
        vo.setNextExecTime(nextExecTime(task, now));
        return vo;
    }

    private List<Long> longs(String json) {
        List<Object> list = DfJson.read(json, new TypeReference<List<Object>>() {
        });
        List<Long> result = new ArrayList<>();
        if (list != null) {
            for (Object o : list) {
                try {
                    result.add(Long.valueOf(String.valueOf(o)));
                } catch (Exception ignore) {
                }
            }
        }
        return result;
    }

    public Long saveTask(TaskInfoVO vo) {
        DfFormMeta meta = dataManage.meta(vo.getFormId());
        access.require(vo.getFormId(), EeConstants.WEIGHT_MANAGE);
        long now = System.currentTimeMillis();
        validateTask(vo, meta, now);
        boolean create = vo.getId() == null;
        DfTask task;
        if (create) {
            task = new DfTask();
            task.setId(IDUtils.snowID());
            task.setFormId(vo.getFormId());
            task.setOrgId(ObjectUtils.defaultIfNull(meta.form().getOrgId(), V3UserUtil.getOid()));
            task.setCreateBy(V3UserUtil.getUid());
            task.setCreateTime(now);
            task.setStatus(DfConstants.TASK_WAITING);
        } else {
            task = task(vo.getId());
            if (!Objects.equals(task.getFormId(), vo.getFormId())) {
                access.deny();
            }
            if (!Objects.equals(task.getStatus(), DfConstants.TASK_STOPPED)) {
                task.setStatus(DfConstants.TASK_WAITING);
            }
        }
        task.setName(StringUtils.trim(vo.getName()));
        task.setFillType(ObjectUtils.defaultIfNull(vo.getFillType(), DfConstants.FILL_APPEND));
        boolean append = task.getFillType() == DfConstants.FILL_APPEND;
        task.setUidList(DfJson.write(append ? ObjectUtils.defaultIfNull(vo.getUidList(), List.of()) : List.of()));
        task.setRidList(DfJson.write(append ? ObjectUtils.defaultIfNull(vo.getRidList(), List.of()) : List.of()));
        task.setFitType(ObjectUtils.defaultIfNull(vo.getFitType(), 0));
        task.setFitColumn(append ? "" : vo.getFitColumn());
        task.setFormExtSetting(StringUtils.defaultIfBlank(vo.getFormExtSetting(), "[]"));
        task.setFormFilterSetting(StringUtils.defaultIfBlank(vo.getFormFilterSetting(), "[]"));
        task.setRateType(ObjectUtils.defaultIfNull(vo.getRateType(), 0));
        task.setOneTimeType(ObjectUtils.defaultIfNull(vo.getOneTimeType(), 0));
        task.setRateVal(StringUtils.defaultString(vo.getRateVal()));
        task.setStartTime(vo.getStartTime());
        task.setEndTime(vo.getEndTime());
        task.setPublishRangeTime(ObjectUtils.defaultIfNull(vo.getPublishRangeTime(), 1));
        task.setPublishRangeTimeType(ObjectUtils.defaultIfNull(vo.getPublishRangeTimeType(), 0));
        List<Integer> flags = new ArrayList<>(ObjectUtils.defaultIfNull(vo.getReciFlagList(), new ArrayList<>()));
        if (!flags.contains(0)) {
            flags.addFirst(0);
        }
        task.setReciFlagList(DfJson.write(flags));
        task.setMsgType(ObjectUtils.defaultIfNull(vo.getMsgType(), 0));
        task.setMsgTitle(vo.getMsgTitle());
        task.setMsgContent(vo.getMsgContent());
        task.setUpdateBy(V3UserUtil.getUid());
        task.setUpdateTime(now);
        taskRepository.save(task);
        boolean immediate = task.getRateType() == 0 && task.getOneTimeType() == 0
                && !Objects.equals(task.getStatus(), DfConstants.TASK_STOPPED) && (create || task.getLastExecTime() == null);
        schedule(task, true);
        if (immediate) {
            distribute(task, now, task.getEndTime());
            markOneTimeFinished(task.getId());
        }
        return task.getId();
    }

    private void validateTask(TaskInfoVO vo, DfFormMeta meta, long now) {
        if (StringUtils.isBlank(vo.getName())) {
            DEException.throwException(Translator.get("ee_df_task_name_required"));
        }
        int fillType = ObjectUtils.defaultIfNull(vo.getFillType(), DfConstants.FILL_APPEND);
        if (fillType == DfConstants.FILL_APPEND) {
            if (CollectionUtils.isEmpty(vo.getUidList()) && CollectionUtils.isEmpty(vo.getRidList())) {
                DEException.throwException(Translator.get("ee_df_task_receiver_required"));
            }
        } else {
            if (StringUtils.isBlank(vo.getFitColumn()) || meta.column(vo.getFitColumn()) == null) {
                DEException.throwException(Translator.get("ee_df_task_fit_column_invalid"));
            }
        }
        int rateType = ObjectUtils.defaultIfNull(vo.getRateType(), 0);
        if (rateType == 0) {
            if (vo.getEndTime() == null || vo.getEndTime() <= now) {
                DEException.throwException(Translator.get("ee_df_task_end_time_invalid"));
            }
            if (ObjectUtils.defaultIfNull(vo.getOneTimeType(), 0) == 1) {
                if (vo.getStartTime() == null || vo.getStartTime() >= vo.getEndTime()) {
                    DEException.throwException(Translator.get("ee_df_task_start_time_invalid"));
                }
            }
        } else {
            if (StringUtils.isBlank(vo.getRateVal())) {
                DEException.throwException(Translator.get("ee_df_task_rate_invalid"));
            }
            try {
                CronUtils.getCronTrigger(CronUtils.cron(rateType, vo.getRateVal()));
            } catch (Exception e) {
                DEException.throwException(Translator.get("ee_df_task_rate_invalid"));
            }
            if (vo.getStartTime() != null && vo.getEndTime() != null && vo.getEndTime() <= vo.getStartTime()) {
                DEException.throwException(Translator.get("ee_df_task_end_time_invalid"));
            }
        }
    }

    private String cron(DfTask task) {
        return CronUtils.cron(task.getRateType(), task.getRateVal());
    }

    private String onceCron(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        return c.get(Calendar.SECOND) + " " + c.get(Calendar.MINUTE) + " " + c.get(Calendar.HOUR_OF_DAY) + " "
                + c.get(Calendar.DAY_OF_MONTH) + " " + (c.get(Calendar.MONTH) + 1) + " ? " + c.get(Calendar.YEAR);
    }

    /**
     * 按任务设置注册/移除定时任务
     */
    public void schedule(DfTask task) {
        schedule(task, false);
    }

    public void schedule(DfTask task, boolean strict) {
        try {
            executor.removeTask(task.getId(), false);
            executor.removeTask(task.getId(), true);
            if (Objects.equals(task.getStatus(), DfConstants.TASK_STOPPED) || Objects.equals(task.getStatus(), DfConstants.TASK_FINISHED)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (task.getRateType() == null || task.getRateType() == 0) {
                if (Objects.equals(task.getOneTimeType(), 1) && task.getLastExecTime() == null
                        && task.getStartTime() != null && task.getStartTime() > now) {
                    // 触发器起点需早于触发时刻，否则按 cron 计算不出下一次触发时间
                    long fireAt = task.getStartTime() / 1000 * 1000;
                    executor.addOrUpdateTask(task.getId(), onceCron(fireAt), fireAt - 1000, null);
                }
                return;
            }
            executor.addOrUpdateTask(task.getId(), cron(task), task.getStartTime(), task.getEndTime());
        } catch (Exception e) {
            LogUtil.error("schedule data filling task failed: " + e.getMessage(), e);
            if (strict) {
                DEException.throwException(Translator.get("ee_df_task_rate_invalid") + ": " + e.getMessage());
            }
        }
    }

    public void executeNow(TaskInfoVO vo) {
        DfTask task = task(vo.getId());
        if (!Objects.equals(task.getFormId(), vo.getFormId())) {
            access.deny();
        }
        access.require(task.getFormId(), EeConstants.WEIGHT_MANAGE);
        long now = System.currentTimeMillis();
        if (vo.getEndTime() == null || vo.getEndTime() <= now) {
            DEException.throwException(Translator.get("ee_df_task_end_time_invalid"));
        }
        distribute(task, now, vo.getEndTime());
    }

    public void stop(Long formId, Long id) {
        DfTask task = task(id);
        if (!Objects.equals(task.getFormId(), formId)) {
            access.deny();
        }
        task.setStatus(DfConstants.TASK_STOPPED);
        task.setUpdateTime(System.currentTimeMillis());
        taskRepository.save(task);
        schedule(task);
    }

    public void start(Long formId, Long id) {
        DfTask task = task(id);
        if (!Objects.equals(task.getFormId(), formId)) {
            access.deny();
        }
        boolean executedOnce = (task.getRateType() == null || task.getRateType() == 0) && task.getLastExecTime() != null;
        task.setStatus(executedOnce ? DfConstants.TASK_FINISHED : DfConstants.TASK_WAITING);
        task.setUpdateTime(System.currentTimeMillis());
        taskRepository.save(task);
        schedule(task);
    }

    public void deleteTasks(Long formId, List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        for (DfTask task : taskRepository.findAllById(ids)) {
            if (!Objects.equals(task.getFormId(), formId)) {
                continue;
            }
            deleteTask(task);
        }
    }

    private void deleteTask(DfTask task) {
        try {
            executor.removeTask(task.getId(), false);
            executor.removeTask(task.getId(), true);
        } catch (Exception e) {
            LogUtil.warn("remove data filling job failed: " + e.getMessage());
        }
        transactionTemplate.executeWithoutResult(s -> {
            QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
            QDfUserTask qu = QDfUserTask.dfUserTask;
            QDfSubTask qs = QDfSubTask.dfSubTask;
            queryFactory.delete(qi).where(qi.taskId.eq(task.getId())).execute();
            queryFactory.delete(qu).where(qu.taskId.eq(task.getId())).execute();
            queryFactory.delete(qs).where(qs.taskId.eq(task.getId())).execute();
            taskRepository.deleteById(task.getId());
        });
    }

    public void deleteByForm(Long formId) {
        QDfTask q = QDfTask.dfTask;
        for (DfTask task : queryFactory.selectFrom(q).where(q.formId.eq(formId)).fetch()) {
            deleteTask(task);
        }
    }

    public void deleteSubTasks(Long formId, List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        transactionTemplate.executeWithoutResult(s -> {
            QDfSubTask qs = QDfSubTask.dfSubTask;
            List<Long> valid = queryFactory.select(qs.id).from(qs).where(qs.id.in(ids), qs.formId.eq(formId)).fetch();
            if (valid.isEmpty()) {
                return;
            }
            QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
            QDfUserTask qu = QDfUserTask.dfUserTask;
            queryFactory.delete(qi).where(qi.subTaskId.in(valid)).execute();
            queryFactory.delete(qu).where(qu.subTaskId.in(valid)).execute();
            queryFactory.delete(qs).where(qs.id.in(valid)).execute();
        });
    }

    // ------------------------------------------------------------------ status

    private int status(DfTask task, long now) {
        if (Objects.equals(task.getStatus(), DfConstants.TASK_STOPPED)) {
            return DfConstants.TASK_STOPPED;
        }
        if (task.getRateType() == null || task.getRateType() == 0) {
            if (task.getLastExecTime() != null || Objects.equals(task.getStatus(), DfConstants.TASK_FINISHED)) {
                return DfConstants.TASK_FINISHED;
            }
            return DfConstants.TASK_WAITING;
        }
        if (task.getEndTime() != null && task.getEndTime() > 0 && task.getEndTime() < now) {
            return DfConstants.TASK_FINISHED;
        }
        if (task.getStartTime() != null && task.getStartTime() > now) {
            return DfConstants.TASK_WAITING;
        }
        return DfConstants.TASK_RUNNING;
    }

    private Long nextExecTime(DfTask task, long now) {
        int status = status(task, now);
        if (status == DfConstants.TASK_STOPPED || status == DfConstants.TASK_FINISHED) {
            return null;
        }
        if (task.getRateType() == null || task.getRateType() == 0) {
            return Objects.equals(task.getOneTimeType(), 1) ? task.getStartTime() : null;
        }
        try {
            List<Long> times = CronUtils.getNextTriggerTimes(cron(task), task.getStartTime(), task.getEndTime(), 1);
            return times.isEmpty() ? null : times.getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    public PageResult<ReportGridVO> taskPage(Long formId, int goPage, int pageSize, DfTaskInfoRequest request) {
        access.form(formId);
        access.require(formId, EeConstants.WEIGHT_READ);
        QDfTask q = QDfTask.dfTask;
        BooleanExpression where = q.formId.eq(formId);
        if (request != null) {
            if (StringUtils.isNotBlank(request.getKeyword())) {
                where = where.and(q.name.containsIgnoreCase(request.getKeyword().trim()));
            }
            if (CollectionUtils.isNotEmpty(request.getUidList())) {
                where = where.and(q.createBy.in(request.getUidList()));
            }
            if (CollectionUtils.isNotEmpty(request.getLastStatusList())) {
                where = where.and(q.lastExecStatus.in(request.getLastStatusList()));
            }
            if (request.getTimeList() != null && request.getTimeList().size() >= 2) {
                where = where.and(q.createTime.between(request.getTimeList().get(0), request.getTimeList().get(1)));
            }
        }
        boolean asc = request != null && Boolean.FALSE.equals(request.getTimeDesc());
        List<DfTask> list = queryFactory.selectFrom(q).where(where).orderBy(asc ? q.createTime.asc() : q.createTime.desc()).fetch();
        long now = System.currentTimeMillis();
        List<ReportGridVO> vos = new ArrayList<>();
        Map<Long, String> names = users.names(list.stream().map(DfTask::getCreateBy).toList());
        for (DfTask t : list) {
            int status = status(t, now);
            if (request != null && CollectionUtils.isNotEmpty(request.getStatusList()) && !request.getStatusList().contains(status)) {
                continue;
            }
            ReportGridVO vo = new ReportGridVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setLastExecTime(t.getLastExecTime());
            vo.setLastExecStatus(t.getLastExecStatus());
            vo.setStatus(status);
            vo.setNextExecTime(nextExecTime(t, now));
            vo.setCreator(names.get(t.getCreateBy()));
            vo.setCreateTime(t.getCreateTime());
            vos.add(vo);
        }
        return page(vos, goPage, pageSize);
    }

    private <T> PageResult<T> page(List<T> all, int goPage, int pageSize) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResult<>(new ArrayList<>(all.subList(from, to)), (long) all.size(), PageRequest.of(page - 1, size));
    }

    public String logMsg(ReportInstanceMsgRequest request) {
        if (request == null || request.getTaskId() == null) {
            return null;
        }
        DfTask task = task(request.getTaskId());
        access.require(task.getFormId(), EeConstants.WEIGHT_READ);
        QDfSubTask q = QDfSubTask.dfSubTask;
        DfSubTask sub = queryFactory.selectFrom(q)
                .where(request.getInstanceId() != null ? q.id.eq(request.getInstanceId()) : q.taskId.eq(task.getId()),
                        q.execStatus.eq(DfConstants.EXEC_FAILED))
                .orderBy(q.createTime.desc()).fetchFirst();
        if (sub != null && StringUtils.isNotBlank(sub.getErrorMsg())) {
            return sub.getErrorMsg();
        }
        return task.getErrorMsg();
    }

    // ------------------------------------------------------------------ sub task

    public PageResult<DfSubTaskVo> subTaskPage(int goPage, int pageSize, DfSubTaskInfoRequest request) {
        DfTask task = task(request == null ? null : request.getTaskId());
        access.require(task.getFormId(), EeConstants.WEIGHT_READ);
        QDfSubTask q = QDfSubTask.dfSubTask;
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        Long total = queryFactory.select(q.id.count()).from(q).where(q.taskId.eq(task.getId())).fetchOne();
        List<DfSubTask> list = queryFactory.selectFrom(q).where(q.taskId.eq(task.getId()))
                .orderBy(q.createTime.desc(), q.id.desc()).offset((long) (page - 1) * size).limit(size).fetch();
        long now = System.currentTimeMillis();
        List<Long> subIds = list.stream().map(DfSubTask::getId).toList();
        Map<Long, List<DfUserTask>> userTasks = new HashMap<>();
        Map<Long, List<DfUserTaskItem>> items = new HashMap<>();
        if (!subIds.isEmpty()) {
            QDfUserTask qu = QDfUserTask.dfUserTask;
            queryFactory.selectFrom(qu).where(qu.subTaskId.in(subIds)).fetch()
                    .forEach(u -> userTasks.computeIfAbsent(u.getSubTaskId(), k -> new ArrayList<>()).add(u));
            QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
            queryFactory.selectFrom(qi).where(qi.subTaskId.in(subIds)).fetch()
                    .forEach(i -> items.computeIfAbsent(i.getSubTaskId(), k -> new ArrayList<>()).add(i));
        }
        List<DfSubTaskVo> vos = new ArrayList<>();
        for (DfSubTask s : list) {
            List<DfUserTask> uts = userTasks.getOrDefault(s.getId(), List.of());
            List<DfUserTaskItem> its = items.getOrDefault(s.getId(), List.of());
            int totalUsers = uts.size();
            int unfinishedUsers = (int) uts.stream().filter(u -> u.getFinishTime() == null).count();
            int totalCount = its.size();
            int unfinishedCount = (int) its.stream().filter(i -> !Objects.equals(i.getStatus(), DfConstants.ITEM_FINISHED)).count();
            BigDecimal rate;
            boolean update = Objects.equals(s.getFillType(), DfConstants.FILL_UPDATE);
            if (update) {
                rate = totalCount == 0 ? BigDecimal.ZERO : BigDecimal.valueOf((totalCount - unfinishedCount) * 100L)
                        .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
            } else {
                rate = totalUsers == 0 ? BigDecimal.ZERO : BigDecimal.valueOf((totalUsers - unfinishedUsers) * 100L)
                        .divide(BigDecimal.valueOf(totalUsers), 2, RoundingMode.HALF_UP);
            }
            vos.add(new DfSubTaskVo().setId(s.getId()).setTaskId(s.getTaskId()).setStartTime(s.getStartTime()).setEndTime(s.getEndTime())
                    .setExecStatus(s.getExecStatus())
                    .setStatus(s.getEndTime() != null && s.getEndTime() < now ? 0 : 1)
                    .setTotalCount(totalCount).setUnfinishedCount(unfinishedCount).setFinishedRate(rate)
                    .setTotalUserCount(totalUsers).setUnfinishedUserCount(unfinishedUsers).setFillType(s.getFillType()));
        }
        return new PageResult<>(vos, ObjectUtils.defaultIfNull(total, 0L), PageRequest.of(page - 1, size));
    }

    public List<Map<String, Object>> subTaskUsers(Long subTaskId, String type) {
        DfSubTask sub = subTaskRepository.findById(subTaskId).orElse(null);
        if (sub == null) {
            DEException.throwException(Translator.get("ee_df_task_not_exists"));
        }
        access.require(sub.getFormId(), EeConstants.WEIGHT_READ);
        QDfUserTask qu = QDfUserTask.dfUserTask;
        BooleanExpression where = qu.subTaskId.eq(subTaskId);
        if (StringUtils.equals(type, "finished")) {
            where = where.and(qu.finishTime.isNotNull());
        } else if (StringUtils.equals(type, "unfinished")) {
            where = where.and(qu.finishTime.isNull());
        }
        List<Long> uids = queryFactory.select(qu.uid).from(qu).where(where).fetch();
        Map<Long, PerUser> map = users.map(uids);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long uid : uids) {
            PerUser u = map.get(uid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(uid));
            m.put("name", u == null ? String.valueOf(uid) : u.getName());
            m.put("account", u == null ? null : u.getAccount());
            result.add(m);
        }
        return result;
    }

    // ------------------------------------------------------------------ distribution

    /**
     * 定时任务回调
     */
    public boolean executeScheduled(Long taskId) {
        DfTask task = taskId == null ? null : taskRepository.findById(taskId).orElse(null);
        if (task == null || Objects.equals(task.getStatus(), DfConstants.TASK_STOPPED)) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean oneTime = task.getRateType() == null || task.getRateType() == 0;
        if (oneTime) {
            if (task.getLastExecTime() != null) {
                return false;
            }
            distribute(task, now, task.getEndTime());
            markOneTimeFinished(task.getId());
            return false;
        }
        if (task.getEndTime() != null && task.getEndTime() > 0 && task.getEndTime() < now) {
            return false;
        }
        distribute(task, now, now + rangeMillis(task, now));
        return true;
    }

    private void markOneTimeFinished(Long taskId) {
        taskRepository.findById(taskId).ifPresent(t -> {
            if (!Objects.equals(t.getStatus(), DfConstants.TASK_STOPPED)) {
                t.setStatus(DfConstants.TASK_FINISHED);
                taskRepository.save(t);
            }
        });
    }

    private long rangeMillis(DfTask task, long now) {
        int n = Math.max(ObjectUtils.defaultIfNull(task.getPublishRangeTime(), 1), 1);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        switch (ObjectUtils.defaultIfNull(task.getPublishRangeTimeType(), 0)) {
            case -1 -> c.add(Calendar.HOUR_OF_DAY, n);
            case 1 -> c.add(Calendar.WEEK_OF_YEAR, n);
            case 2 -> c.add(Calendar.MONTH, n);
            default -> c.add(Calendar.DAY_OF_MONTH, n);
        }
        return c.getTimeInMillis() - now;
    }

    /**
     * 下发一次：创建子任务、用户任务与任务项
     */
    public Long distribute(DfTask task, long start, Long end) {
        boolean ownContext = V3UserUtil.getUid() == null;
        if (ownContext) {
            V3UserUtil.setContext(task.getCreateBy(), task.getOrgId());
        }
        DfSubTask sub = new DfSubTask();
        sub.setId(IDUtils.snowID());
        sub.setTaskId(task.getId());
        sub.setFormId(task.getFormId());
        sub.setFillType(task.getFillType());
        sub.setStartTime(start);
        sub.setEndTime(end);
        sub.setExecStatus(DfConstants.EXEC_RUNNING);
        sub.setCreateBy(task.getCreateBy());
        sub.setCreateTime(System.currentTimeMillis());
        subTaskRepository.save(sub);
        updateTaskExec(task.getId(), DfConstants.EXEC_RUNNING, null);
        Set<Long> receivers = new HashSet<>();
        try {
            Map<Long, List<String>> assignment = assignment(task);
            if (assignment.isEmpty()) {
                DEException.throwException(Translator.get("ee_df_task_no_receiver"));
            }
            transactionTemplate.executeWithoutResult(s -> {
                long now = System.currentTimeMillis();
                List<DfUserTask> userTasks = new ArrayList<>();
                List<DfUserTaskItem> items = new ArrayList<>();
                for (Map.Entry<Long, List<String>> e : assignment.entrySet()) {
                    DfUserTask ut = new DfUserTask();
                    ut.setId(IDUtils.snowID());
                    ut.setSubTaskId(sub.getId());
                    ut.setTaskId(task.getId());
                    ut.setFormId(task.getFormId());
                    ut.setUid(e.getKey());
                    ut.setAssignBy(task.getCreateBy());
                    ut.setFillType(task.getFillType());
                    ut.setStartTime(start);
                    ut.setEndTime(end);
                    ut.setCreateTime(now);
                    userTasks.add(ut);
                    for (String dataId : e.getValue()) {
                        DfUserTaskItem item = new DfUserTaskItem();
                        item.setId(IDUtils.snowID());
                        item.setUserTaskId(ut.getId());
                        item.setSubTaskId(sub.getId());
                        item.setTaskId(task.getId());
                        item.setFormId(task.getFormId());
                        item.setUid(e.getKey());
                        item.setDataId(dataId);
                        item.setStatus(DfConstants.ITEM_OPEN);
                        item.setCreateTime(now);
                        items.add(item);
                    }
                }
                userTaskRepository.saveAll(userTasks);
                itemRepository.saveAll(items);
            });
            receivers.addAll(assignment.keySet());
            sub.setExecStatus(DfConstants.EXEC_SUCCESS);
            subTaskRepository.save(sub);
            updateTaskExec(task.getId(), DfConstants.EXEC_SUCCESS, null);
        } catch (Exception e) {
            LogUtil.error("data filling task distribute failed: " + e.getMessage(), e);
            String msg = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            sub.setExecStatus(DfConstants.EXEC_FAILED);
            sub.setErrorMsg(msg);
            subTaskRepository.save(sub);
            updateTaskExec(task.getId(), DfConstants.EXEC_FAILED, msg);
        } finally {
            if (ownContext) {
                V3UserUtil.clear();
            }
        }
        notifyUsers(task, sub, receivers);
        return sub.getId();
    }

    private void updateTaskExec(Long taskId, int status, String error) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setLastExecStatus(status);
            t.setLastExecTime(System.currentTimeMillis());
            if (error != null) {
                t.setErrorMsg(error);
            }
            taskRepository.save(t);
        });
    }

    private void notifyUsers(DfTask task, DfSubTask sub, Set<Long> receivers) {
        try {
            String payload = DfJson.write(Map.of("taskId", String.valueOf(task.getId()), "instanceId", String.valueOf(sub.getId())));
            if (task.getCreateBy() != null) {
                wsService.releaseMessage(new WsMessage<>(task.getCreateBy(), DfConstants.WS_REPORT_NOTICE, payload));
            }
            for (Long uid : receivers) {
                wsService.releaseMessage(new WsMessage<>(uid, DfConstants.WS_MSG_TOPIC, "refresh"));
            }
        } catch (Exception e) {
            LogUtil.warn("data filling websocket notice failed: " + e.getMessage());
        }
    }

    /**
     * 计算接收人：新增数据方式为用户/角色；更新数据方式按匹配列把数据分配给用户
     */
    private Map<Long, List<String>> assignment(DfTask task) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (!Objects.equals(task.getFillType(), DfConstants.FILL_UPDATE)) {
            Set<Long> uids = new LinkedHashSet<>(users.enabled(longs(task.getUidList())));
            uids.addAll(users.usersOfRoles(longs(task.getRidList()), task.getOrgId()));
            uids.forEach(uid -> result.put(uid, new ArrayList<>()));
            return result;
        }
        DfFormMeta meta = dataManage.meta(task.getFormId());
        DfFormMeta.Column fit = meta.column(task.getFitColumn());
        if (fit == null) {
            DEException.throwException(Translator.get("ee_df_task_fit_column_invalid"));
        }
        Map<String, Set<Long>> userIndex = new HashMap<>();
        boolean byName = Objects.equals(task.getFitType(), 1);
        for (PerUser u : users.orgUsers(task.getOrgId())) {
            String key = byName ? u.getName() : u.getAccount();
            if (StringUtils.isNotBlank(key)) {
                userIndex.computeIfAbsent(key.trim(), k -> new LinkedHashSet<>()).add(u.getId());
            }
        }
        List<FilterSetting> filters = DfJson.read(task.getFormFilterSetting(), new TypeReference<List<FilterSetting>>() {
        });
        try (DfSession session = jdbcSupport.open(meta.form().getDatasource())) {
            List<Object> params = new ArrayList<>();
            String where = filterWhere(session, meta, filters, params);
            String sql = "SELECT " + session.q(meta.pk()) + ", " + session.q(fit.name()) + " FROM " + session.q(meta.table())
                    + (StringUtils.isBlank(where) ? "" : " WHERE " + where);
            for (List<String> row : session.queryStrings(sql, params, 0)) {
                String pk = row.get(0);
                String fitValue = row.get(1);
                if (pk == null || StringUtils.isBlank(fitValue)) {
                    continue;
                }
                Set<Long> matched = userIndex.get(fitValue.trim());
                if (matched == null) {
                    continue;
                }
                for (Long uid : matched) {
                    result.computeIfAbsent(uid, k -> new ArrayList<>()).add(pk);
                }
            }
        }
        return result;
    }

    private String filterWhere(DfSession session, DfFormMeta meta, List<FilterSetting> filters, List<Object> params) {
        if (CollectionUtils.isEmpty(filters)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (FilterSetting f : filters) {
            DfFormMeta.Column c = f == null ? null : meta.column(f.getColumn());
            if (c == null) {
                continue;
            }
            String col = session.q(c.name());
            if (StringUtils.equals(f.getFilterType(), "enum")) {
                if (CollectionUtils.isEmpty(f.getValueList())) {
                    continue;
                }
                if (f.isMultiple() || c.multiple()) {
                    List<String> ors = new ArrayList<>();
                    for (String v : f.getValueList()) {
                        ors.add(col + " LIKE ?");
                        params.add("%" + DfJson.write(v) + "%");
                    }
                    parts.add("(" + String.join(" OR ", ors) + ")");
                } else {
                    List<String> marks = new ArrayList<>();
                    for (String v : f.getValueList()) {
                        marks.add("?");
                        params.add(DfValues.toDb(v, c.type(), c.label()));
                    }
                    parts.add(col + " IN (" + String.join(", ", marks) + ")");
                }
                continue;
            }
            String term = StringUtils.defaultIfBlank(f.getTerm(), "eq");
            switch (term) {
                case "null" -> parts.add(col + " IS NULL");
                case "not_null" -> parts.add(col + " IS NOT NULL");
                case "like" -> {
                    parts.add(col + " LIKE ?");
                    params.add("%" + StringUtils.defaultString(Objects.toString(f.getValue(), "")) + "%");
                }
                case "not like" -> {
                    parts.add(col + " NOT LIKE ?");
                    params.add("%" + StringUtils.defaultString(Objects.toString(f.getValue(), "")) + "%");
                }
                case "between" -> {
                    Object lo = f.getMin();
                    Object hi = f.getMax();
                    if (f.getValue() instanceof List<?> list && list.size() >= 2) {
                        lo = list.get(0);
                        hi = list.get(1);
                    }
                    if (lo != null) {
                        parts.add(col + " >= ?");
                        params.add(DfValues.toDb(lo, c.type(), c.label()));
                    }
                    if (hi != null) {
                        parts.add(col + " <= ?");
                        params.add(DfValues.toDb(hi, c.type(), c.label()));
                    }
                }
                default -> {
                    if (f.getValue() == null) {
                        continue;
                    }
                    String op = switch (term) {
                        case "not_eq" -> "<>";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "le" -> "<=";
                        case "ge" -> ">=";
                        default -> "=";
                    };
                    parts.add(col + " " + op + " ?");
                    params.add(DfValues.toDb(f.getValue(), c.type(), c.label()));
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    /**
     * 启动时重新注册所有有效任务
     */
    public void init() {
        for (DfTask task : taskRepository.findAll()) {
            schedule(task);
        }
    }

    // ------------------------------------------------------------------ filler side

    private DfUserTask ownUserTask(Long id) {
        DfUserTask ut = id == null ? null : userTaskRepository.findById(id).orElse(null);
        if (ut == null) {
            DEException.throwException(Translator.get("ee_df_task_not_exists"));
        }
        if (!Objects.equals(ut.getUid(), V3UserUtil.getUid())) {
            access.deny();
        }
        return ut;
    }

    private void checkNotExpired(DfUserTask ut) {
        if (ut.getEndTime() != null && ut.getEndTime() <= System.currentTimeMillis()) {
            DEException.throwException(Translator.get("ee_df_task_expired"));
        }
    }

    /**
     * 待填报任务数（消息中心角标使用）
     */
    public long countTodo(Long uid, Long oid) {
        if (uid == null) {
            return 0L;
        }
        QDfUserTask q = QDfUserTask.dfUserTask;
        long now = System.currentTimeMillis();
        Long count = queryFactory.select(q.id.count()).from(q)
                .where(q.uid.eq(uid), q.finishTime.isNull(), q.endTime.isNull().or(q.endTime.gt(now))).fetchOne();
        return ObjectUtils.defaultIfNull(count, 0L);
    }

    public long countCurrentUserTodo() {
        return countTodo(V3UserUtil.getUid(), V3UserUtil.getOid());
    }

    public PageResult<DfUserTaskVo> userTaskPage(int goPage, int pageSize, DfUserTaskRequest request) {
        Long uid = V3UserUtil.getUid();
        QDfUserTask q = QDfUserTask.dfUserTask;
        QDfTask qt = QDfTask.dfTask;
        long now = System.currentTimeMillis();
        BooleanExpression where = q.uid.eq(uid);
        String type = request == null ? "todo" : StringUtils.defaultIfBlank(request.getType(), "todo");
        switch (type) {
            case "finished" -> where = where.and(q.finishTime.isNotNull());
            case "expired" -> where = where.and(q.finishTime.isNull()).and(q.endTime.loe(now));
            default -> where = where.and(q.finishTime.isNull()).and(q.endTime.isNull().or(q.endTime.gt(now)));
        }
        if (request != null && StringUtils.isNotBlank(request.getTaskName())) {
            where = where.and(qt.name.containsIgnoreCase(request.getTaskName().trim()));
        }
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        Long total = queryFactory.select(q.id.count()).from(q).innerJoin(qt).on(qt.id.eq(q.taskId)).where(where).fetchOne();
        var rows = queryFactory.select(q, qt.name).from(q).innerJoin(qt).on(qt.id.eq(q.taskId)).where(where)
                .orderBy("finished".equals(type) ? q.finishTime.desc() : q.startTime.desc(), q.id.desc())
                .offset((long) (page - 1) * size).limit(size).fetch();
        List<Long> ids = rows.stream().map(r -> r.get(q).getId()).toList();
        Map<Long, long[]> counts = itemCounts(ids);
        Map<Long, String> names = users.names(rows.stream().map(r -> r.get(q).getAssignBy()).toList());
        List<DfUserTaskVo> vos = new ArrayList<>();
        for (var r : rows) {
            DfUserTask ut = r.get(q);
            long[] c = counts.getOrDefault(ut.getId(), new long[]{0, 0});
            vos.add(new DfUserTaskVo().setId(ut.getId()).setTaskId(ut.getTaskId()).setFormId(ut.getFormId()).setTaskName(r.get(qt.name))
                    .setStartTime(ut.getStartTime()).setEndTime(ut.getEndTime()).setFinishTime(ut.getFinishTime())
                    .setAssignBy(ut.getAssignBy()).setAssigner(names.get(ut.getAssignBy()))
                    .setTotalCount(c[0]).setFinishCount(c[1])
                    .setExpired(ut.getEndTime() != null && ut.getEndTime() <= now)
                    .setFillType(ut.getFillType()));
        }
        return new PageResult<>(vos, ObjectUtils.defaultIfNull(total, 0L), PageRequest.of(page - 1, size));
    }

    /**
     * 用户任务的 [总项数, 已完成项数]
     */
    private Map<Long, long[]> itemCounts(List<Long> userTaskIds) {
        Map<Long, long[]> result = new HashMap<>();
        if (userTaskIds.isEmpty()) {
            return result;
        }
        QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
        for (DfUserTaskItem item : queryFactory.selectFrom(qi).where(qi.userTaskId.in(userTaskIds)).fetch()) {
            long[] c = result.computeIfAbsent(item.getUserTaskId(), k -> new long[]{0, 0});
            c[0]++;
            if (Objects.equals(item.getStatus(), DfConstants.ITEM_FINISHED)) {
                c[1]++;
            }
        }
        return result;
    }

    private List<DfUserTaskItem> items(Long userTaskId) {
        QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
        return queryFactory.selectFrom(qi).where(qi.userTaskId.eq(userTaskId)).orderBy(qi.createTime.asc(), qi.id.asc()).fetch();
    }

    public DfUserTaskData userTaskData(Long id) {
        DfUserTask ut = ownUserTask(id);
        DfFormMeta meta = dataManage.meta(ut.getFormId());
        DfTask task = taskRepository.findById(ut.getTaskId()).orElse(null);
        List<DfUserTaskItem> items = items(ut.getId());
        List<DfUserTaskData.DfSubInstance> instances = new ArrayList<>();
        List<String> dataIds = new ArrayList<>();
        for (DfUserTaskItem i : items) {
            instances.add(new DfUserTaskData.DfSubInstance().setId(i.getId()).setTaskId(i.getTaskId()).setPid(i.getUserTaskId())
                    .setUid(i.getUid()).setFormId(i.getFormId()).setDataId(i.getDataId()).setFinishTime(i.getFinishTime())
                    .setStatus(i.getStatus()));
            if (i.getDataId() != null) {
                dataIds.add(i.getDataId());
            }
        }
        return new DfUserTaskData().setFormId(ut.getFormId()).setFormTitle(meta.form().getName()).setFillType(ut.getFillType())
                .setForm(meta.form().getForms()).setFormExtSetting(task == null ? null : task.getFormExtSetting())
                .setDataIds(dataIds).setSubInstances(instances);
    }

    public String template(Long itemId) {
        DfUserTaskItem item = itemId == null ? null : itemRepository.findById(itemId).orElse(null);
        Long taskId;
        Long uid;
        Long formId;
        if (item != null) {
            taskId = item.getTaskId();
            uid = item.getUid();
            formId = item.getFormId();
        } else {
            DfUserTask ut = itemId == null ? null : userTaskRepository.findById(itemId).orElse(null);
            if (ut == null) {
                return null;
            }
            taskId = ut.getTaskId();
            uid = ut.getUid();
            formId = ut.getFormId();
        }
        if (!Objects.equals(uid, V3UserUtil.getUid()) && !access.can(formId, EeConstants.WEIGHT_READ)) {
            access.deny();
        }
        return taskRepository.findById(taskId).map(DfTask::getFormExtSetting).orElse(null);
    }

    private DfDataManage.SaveOptions options(DfUserTask ut) {
        DfDataManage.SaveOptions options = new DfDataManage.SaveOptions();
        options.userTaskId = ut.getId();
        DfTask task = taskRepository.findById(ut.getTaskId()).orElse(null);
        if (task != null) {
            List<ExtFormSettings> settings = DfJson.read(task.getFormExtSetting(), new TypeReference<List<ExtFormSettings>>() {
            });
            if (settings != null) {
                options.templateSettings = settings;
                if (Objects.equals(ut.getFillType(), DfConstants.FILL_UPDATE)) {
                    DfFormMeta meta = dataManage.meta(ut.getFormId());
                    for (ExtFormSettings s : settings) {
                        if (s.isDisable() && meta.field(s.getId()) != null) {
                            meta.columnsOf(meta.field(s.getId())).forEach(c -> options.disabledColumns.add(c.name().toLowerCase()));
                        }
                    }
                }
            }
        }
        return options;
    }

    public DataFillFormTableDataResponse saveItemData(Long itemId, Map<String, Object> data) {
        DfUserTaskItem item = itemId == null ? null : itemRepository.findById(itemId).orElse(null);
        if (item == null) {
            DEException.throwException(Translator.get("ee_df_task_not_exists"));
        }
        DfUserTask ut = ownUserTask(item.getUserTaskId());
        checkNotExpired(ut);
        DfDataManage.SaveOptions options = options(ut);
        if (item.getDataId() != null) {
            options.expectedPk = item.getDataId();
            options.updateOnly = true;
        } else {
            options.insertOnly = true;
        }
        DataFillFormTableDataResponse response = dataManage.saveRow(ut.getFormId(), data, options);
        if (item.getDataId() == null && response.getData() instanceof Map<?, ?> row) {
            Object pk = row.get(response.getKey());
            item.setDataId(pk == null ? null : String.valueOf(pk));
        }
        item.setStatus(DfConstants.ITEM_FINISHED);
        item.setFinishTime(System.currentTimeMillis());
        itemRepository.save(item);
        refreshFinish(ut);
        return response;
    }

    public DataFillFormTableDataResponse appendData(Long userTaskId, Map<String, Object> data) {
        DfUserTask ut = ownUserTask(userTaskId);
        checkNotExpired(ut);
        if (Objects.equals(ut.getFillType(), DfConstants.FILL_UPDATE)) {
            access.deny();
        }
        DfDataManage.SaveOptions options = options(ut);
        options.insertOnly = true;
        DataFillFormTableDataResponse response = dataManage.saveRow(ut.getFormId(), data, options);
        Object pk = response.getData() instanceof Map<?, ?> row ? row.get(response.getKey()) : null;
        addItems(ut, pk == null ? List.of() : List.of(String.valueOf(pk)));
        return response;
    }

    private void addItems(DfUserTask ut, List<String> dataIds) {
        long now = System.currentTimeMillis();
        List<DfUserTaskItem> items = new ArrayList<>();
        for (String dataId : dataIds) {
            DfUserTaskItem item = new DfUserTaskItem();
            item.setId(IDUtils.snowID());
            item.setUserTaskId(ut.getId());
            item.setSubTaskId(ut.getSubTaskId());
            item.setTaskId(ut.getTaskId());
            item.setFormId(ut.getFormId());
            item.setUid(ut.getUid());
            item.setDataId(dataId);
            item.setStatus(DfConstants.ITEM_FINISHED);
            item.setFinishTime(now);
            item.setCreateTime(now);
            items.add(item);
        }
        itemRepository.saveAll(items);
        refreshFinish(ut);
    }

    public void deleteUserTaskRow(Long userTaskId, String dataId) {
        DfUserTask ut = ownUserTask(userTaskId);
        checkNotExpired(ut);
        if (Objects.equals(ut.getFillType(), DfConstants.FILL_UPDATE)) {
            access.deny();
        }
        QDfUserTaskItem qi = QDfUserTaskItem.dfUserTaskItem;
        DfUserTaskItem item = queryFactory.selectFrom(qi).where(qi.userTaskId.eq(ut.getId()), qi.dataId.eq(dataId)).fetchFirst();
        if (item == null) {
            access.deny();
        }
        dataManage.deleteRows(ut.getFormId(), List.of(dataId), ut.getId());
        itemRepository.deleteById(item.getId());
        refreshFinish(ut);
    }

    public void appendConfirmUpload(Long userTaskId, Long formId, Map<String, String> data) {
        DfUserTask ut = ownUserTask(userTaskId);
        checkNotExpired(ut);
        if (!Objects.equals(ut.getFormId(), formId) || Objects.equals(ut.getFillType(), DfConstants.FILL_UPDATE)) {
            access.deny();
        }
        List<String> keys = excelManage.confirm(formId, data, ut.getId());
        addItems(ut, keys);
    }

    /**
     * 当前用户是否有该表单未过期的新增数据任务
     */
    public boolean hasActiveAppendTask(Long formId) {
        QDfUserTask q = QDfUserTask.dfUserTask;
        long now = System.currentTimeMillis();
        return queryFactory.selectOne().from(q).where(q.uid.eq(V3UserUtil.getUid()), q.formId.eq(formId),
                q.fillType.ne(DfConstants.FILL_UPDATE), q.endTime.isNull().or(q.endTime.gt(now))).fetchFirst() != null;
    }

    /**
     * 新增方式：有数据即完成；更新方式：全部数据项已填报才完成
     */
    private void refreshFinish(DfUserTask ut) {
        Map<Long, long[]> counts = itemCounts(List.of(ut.getId()));
        long[] c = counts.getOrDefault(ut.getId(), new long[]{0, 0});
        boolean finished = Objects.equals(ut.getFillType(), DfConstants.FILL_UPDATE) ? c[0] > 0 && c[1] >= c[0] : c[0] > 0;
        transactionTemplate.executeWithoutResult(s -> {
            QDfUserTask q = QDfUserTask.dfUserTask;
            if (finished) {
                queryFactory.update(q).set(q.finishTime, System.currentTimeMillis()).where(q.id.eq(ut.getId())).execute();
            } else {
                queryFactory.update(q).setNull(q.finishTime).where(q.id.eq(ut.getId())).execute();
            }
        });
    }
}
