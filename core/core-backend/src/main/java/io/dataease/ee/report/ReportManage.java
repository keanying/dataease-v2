package io.dataease.ee.report;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.report.dto.*;
import io.dataease.api.report.vo.ReportGridVO;
import io.dataease.api.report.vo.ReportInfoVO;
import io.dataease.api.report.vo.ReportInstanceVO;
import io.dataease.api.visualization.vo.VisualizationReportFilterVO;
import io.dataease.dao.auto.entity.VisualizationReportFilter;
import io.dataease.dao.auto.repo.VisualizationReportFilterRepository;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.report.dao.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.job.schedule.DeTaskExecutor;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 定时报告任务、执行记录管理
 */
@Component
public class ReportManage {

    private static final Pattern PIXEL = Pattern.compile("^\\s*(\\d{3,5})\\s*\\*\\s*(\\d{3,5})\\s*$");

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+$");

    private static final long DAY = 24L * 3600 * 1000;

    private final JPAQueryFactory queryFactory;

    private final XpackReportTaskRepository taskRepository;

    private final XpackReportInstanceRepository instanceRepository;

    private final VisualizationReportFilterRepository reportFilterRepository;

    private final EePermissionManage permissionManage;

    private final DeTaskExecutor deTaskExecutor;

    private final TransactionTemplate transactionTemplate;

    public ReportManage(TransactionTemplate transactionTemplate, JPAQueryFactory queryFactory, XpackReportTaskRepository taskRepository,
                        XpackReportInstanceRepository instanceRepository,
                        VisualizationReportFilterRepository reportFilterRepository,
                        EePermissionManage permissionManage, @Lazy DeTaskExecutor deTaskExecutor) {
        this.transactionTemplate = transactionTemplate;
        this.queryFactory = queryFactory;
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.reportFilterRepository = reportFilterRepository;
        this.permissionManage = permissionManage;
        this.deTaskExecutor = deTaskExecutor;
    }

    // ------------------------------------------------------------------ 权限

    private boolean orgAdmin() {
        return permissionManage.isOrgAdmin(V3UserUtil.getUid(), V3UserUtil.getOid());
    }

    /**
     * 当前用户可见的任务条件：当前组织；非组织管理员只看自己创建的
     */
    private BooleanBuilder visibleTask(QXpackReportTask t) {
        BooleanBuilder builder = new BooleanBuilder();
        Long oid = V3UserUtil.getOid();
        builder.and(oid == null ? Expressions.FALSE.isTrue() : t.oid.eq(oid));
        if (!orgAdmin()) {
            builder.and(t.creator.eq(V3UserUtil.getUid()));
        }
        return builder;
    }

    public XpackReportTask requireTask(Long taskId) {
        XpackReportTask task = taskId == null ? null : taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            DEException.throwException(Translator.get("ee_report_task_not_exist"));
        }
        if (!Objects.equals(task.getOid(), V3UserUtil.getOid())
                || (!Objects.equals(task.getCreator(), V3UserUtil.getUid()) && !orgAdmin())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return task;
    }

    public static int resourceType(Integer rtid) {
        return Objects.equals(rtid, ReportConstants.RTID_SCREEN) ? EeConstants.RT_SCREEN : EeConstants.RT_PANEL;
    }

    // ------------------------------------------------------------------ 任务

    public PageResult<ReportGridVO> pager(int goPage, int pageSize, ReportGridRequest request) {
        markFinished();
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        BooleanBuilder where = visibleTask(t);
        if (request != null) {
            if (StringUtils.isNotBlank(request.getKeyword())) {
                where.and(t.name.like("%" + request.getKeyword().trim() + "%"));
            }
            if (CollectionUtils.isNotEmpty(request.getLastStatusList())) {
                where.and(t.lastExecStatus.in(request.getLastStatusList()));
            }
            if (CollectionUtils.isNotEmpty(request.getStatusList())) {
                where.and(t.status.in(request.getStatusList()));
            }
            if (CollectionUtils.isNotEmpty(request.getUidList())) {
                where.and(t.creator.in(request.getUidList()));
            }
            List<Long> timeList = request.getTimeList();
            if (CollectionUtils.isNotEmpty(timeList) && timeList.size() >= 2 && timeList.get(0) != null && timeList.get(1) != null) {
                where.and(t.createTime.between(timeList.get(0), timeList.get(1)));
            }
        }
        JPAQuery<XpackReportTask> query = queryFactory.selectFrom(t).where(where);
        long total = query.clone().fetchCount();
        if (request != null && request.getTimeDesc() != null) {
            query.orderBy(request.getTimeDesc() ? t.lastExecTime.desc() : t.lastExecTime.asc(), t.createTime.desc());
        } else {
            query.orderBy(t.createTime.desc());
        }
        List<XpackReportTask> tasks = query.offset((long) (page - 1) * size).limit(size).fetch();
        Map<Long, String> names = userNames(tasks.stream().map(XpackReportTask::getCreator).collect(Collectors.toSet()));
        List<ReportGridVO> records = new ArrayList<>();
        for (XpackReportTask task : tasks) {
            ReportGridVO vo = new ReportGridVO();
            vo.setId(task.getId());
            vo.setName(task.getName());
            vo.setLastExecTime(task.getLastExecTime());
            vo.setLastExecStatus(task.getLastExecStatus());
            vo.setStatus(task.getStatus());
            vo.setCreator(names.get(task.getCreator()));
            vo.setCreateTime(task.getCreateTime());
            if (task.getStatus() != null && task.getStatus() < ReportConstants.STATUS_STOPPED) {
                vo.setNextExecTime(nextExecTime(task));
            }
            records.add(vo);
        }
        return new PageResult<>(records, total, PageRequest.of(page - 1, size));
    }

    public Long nextExecTime(XpackReportTask task) {
        try {
            return EeRateUtils.nextFireTime(EeRateUtils.reportCron(task.getRateType(), task.getRateVal()), task.getStartTime(), task.getEndTime());
        } catch (Exception e) {
            return null;
        }
    }

    public Map<Long, String> userNames(Collection<Long> uids) {
        Map<Long, String> map = new HashMap<>();
        Set<Long> ids = uids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return map;
        }
        QPerUser u = QPerUser.perUser;
        queryFactory.select(u.id, u.name).from(u).where(u.id.in(ids)).fetch()
                .forEach(tuple -> map.put(tuple.get(u.id), tuple.get(u.name)));
        return map;
    }

    /**
     * 结束时间已过的等待中任务标记为已结束
     */
    public void markFinished() {
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        transactionTemplate.executeWithoutResult(s -> queryFactory.update(t).set(t.status, ReportConstants.STATUS_FINISHED)
                .where(t.status.eq(ReportConstants.STATUS_WAITING), t.endTime.isNotNull(), t.endTime.lt(System.currentTimeMillis()))
                .execute());
    }

    @Transactional
    public Long create(ReportCreator creator) {
        validate(creator, true);
        XpackReportTask task = new XpackReportTask();
        fill(task, creator);
        long now = System.currentTimeMillis();
        task.setId(IDUtils.snowID());
        task.setStatus(ReportConstants.STATUS_WAITING);
        task.setOid(V3UserUtil.getOid());
        task.setCreator(V3UserUtil.getUid());
        task.setCreateTime(now);
        task.setUpdater(V3UserUtil.getUid());
        task.setUpdateTime(now);
        taskRepository.save(task);
        saveFilters(task, creator.getReportFilter());
        schedule(task);
        return task.getId();
    }

    @Transactional
    public void update(ReportEditor editor) {
        XpackReportTask task = requireTask(editor.getTaskId());
        validate(editor, false);
        Long oldRid = task.getRid();
        fill(task, editor);
        task.setUpdater(V3UserUtil.getUid());
        task.setUpdateTime(System.currentTimeMillis());
        if (!Objects.equals(task.getStatus(), ReportConstants.STATUS_STOPPED) && !Objects.equals(task.getStatus(), ReportConstants.STATUS_RUNNING)) {
            task.setStatus(ReportConstants.STATUS_WAITING);
        }
        taskRepository.save(task);
        if (editor.getEndTime() == null) {
            // 非空合并保存不会清空字段，结束时间被取消时显式置空
            QXpackReportTask t = QXpackReportTask.xpackReportTask;
            queryFactory.update(t).setNull(t.endTime).where(t.id.eq(task.getId())).execute();
            task.setEndTime(null);
        }
        if (editor.getReportFilter() != null) {
            saveFilters(task, editor.getReportFilter());
        } else if (!Objects.equals(oldRid, task.getRid())) {
            reportFilterRepository.deleteByReportId(task.getId());
        }
        deTaskExecutor.removeTask(task.getId(), false);
        if (!Objects.equals(task.getStatus(), ReportConstants.STATUS_STOPPED)) {
            schedule(task);
        }
    }

    /**
     * 注册 Quartz 任务（已过期则标记为已结束）
     */
    public void schedule(XpackReportTask task) {
        if (task.getEndTime() != null && task.getEndTime() < System.currentTimeMillis()) {
            if (Objects.equals(task.getStatus(), ReportConstants.STATUS_WAITING)) {
                updateStatus(task.getId(), ReportConstants.STATUS_FINISHED);
            }
            return;
        }
        String cron = EeRateUtils.reportCron(task.getRateType(), task.getRateVal());
        long start = task.getStartTime() == null ? System.currentTimeMillis() : task.getStartTime();
        deTaskExecutor.addOrUpdateTask(task.getId(), cron, start, task.getEndTime());
    }

    /**
     * 启动时：修正中断的执行状态，并为所有等待中的任务注册定时任务
     */
    public void initSchedules() {
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        resetInterrupted();
        markFinished();
        try {
            deTaskExecutor.clearRetryTask();
        } catch (Exception e) {
            LogUtil.error("clear report retry tasks failed: " + e.getMessage(), e);
        }
        List<XpackReportTask> tasks = queryFactory.selectFrom(t).where(t.status.eq(ReportConstants.STATUS_WAITING)).fetch();
        for (XpackReportTask task : tasks) {
            try {
                schedule(task);
            } catch (Exception e) {
                LogUtil.error("schedule report task " + task.getId() + " failed: " + e.getMessage(), e);
            }
        }
        LogUtil.info("report tasks scheduled: " + tasks.size());
    }

    public void resetInterrupted() {
        transactionTemplate.executeWithoutResult(s -> doResetInterrupted());
    }

    private void doResetInterrupted() {
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
        queryFactory.update(t).set(t.status, ReportConstants.STATUS_WAITING).where(t.status.eq(ReportConstants.STATUS_RUNNING)).execute();
        queryFactory.update(t).set(t.lastExecStatus, ReportConstants.EXEC_FAILED).where(t.lastExecStatus.eq(ReportConstants.EXEC_RUNNING)).execute();
        queryFactory.update(i).set(i.execStatus, ReportConstants.EXEC_FAILED).set(i.msg, Translator.get("ee_report_interrupted"))
                .set(i.endTime, System.currentTimeMillis()).where(i.execStatus.eq(ReportConstants.EXEC_RUNNING)).execute();
    }

    /**
     * 停止资源（仪表板/数据大屏）已被删除的报告任务：移除定时与重试调度、标记为已停止，并写一条失败的执行记录说明原因。
     * <p>由删除仪表板/大屏的扩展（{@code VisualizationResourceTreeExtension.delete}）调用，执行报告时发现资源不存在也会调用。</p>
     *
     * @return 停止的任务数
     */
    public int stopOrphans() {
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        List<XpackReportTask> tasks = queryFactory.selectFrom(t).where(t.status.ne(ReportConstants.STATUS_STOPPED)).fetch();
        Map<Long, Boolean> exists = new HashMap<>();
        int count = 0;
        for (XpackReportTask task : tasks) {
            Long rid = task.getRid();
            boolean alive = rid != null && exists.computeIfAbsent(rid, this::visualizationExists);
            if (!alive) {
                stopForDeletedResource(task, true);
                count++;
            }
        }
        return count;
    }

    private boolean visualizationExists(Long id) {
        io.dataease.dao.auto.entity.QDataVisualizationInfo v = io.dataease.dao.auto.entity.QDataVisualizationInfo.dataVisualizationInfo;
        Long found = queryFactory.select(v.id).from(v)
                .where(v.id.eq(id), v.deleteFlag.isNull().or(v.deleteFlag.eq(false))).fetchFirst();
        return found != null;
    }

    /**
     * 资源已删除：停止任务；log 为 true 时写一条执行记录说明原因（执行报告时发现的由该次执行记录说明）
     */
    public void stopForDeletedResource(XpackReportTask task, boolean log) {
        if (task == null || Objects.equals(task.getStatus(), ReportConstants.STATUS_STOPPED)) {
            return;
        }
        try {
            deTaskExecutor.removeTask(task.getId(), false);
            deTaskExecutor.removeRetryTask(task.getId());
        } catch (Exception e) {
            LogUtil.warn("unschedule report task " + task.getId() + " failed: " + e.getMessage());
        }
        java.util.Locale origin = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        String msg;
        try {
            ReportConstants.applyLocale(task.getCreator());
            msg = Translator.get("ee_report_stopped_resource_deleted");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(origin);
        }
        long now = System.currentTimeMillis();
        transactionTemplate.executeWithoutResult(s -> {
            QXpackReportTask t = QXpackReportTask.xpackReportTask;
            queryFactory.update(t).set(t.status, ReportConstants.STATUS_STOPPED).where(t.id.eq(task.getId())).execute();
            if (!log) {
                return;
            }
            queryFactory.update(t).set(t.lastExecStatus, ReportConstants.EXEC_FAILED).set(t.lastExecTime, now)
                    .where(t.id.eq(task.getId())).execute();
            XpackReportInstance instance = new XpackReportInstance();
            instance.setId(IDUtils.snowID());
            instance.setTaskId(task.getId());
            instance.setStartTime(now);
            instance.setEndTime(now);
            instance.setExecStatus(ReportConstants.EXEC_FAILED);
            instance.setTriggerType(ReportConstants.TRIGGER_SCHEDULE);
            instance.setOid(task.getOid());
            instance.setMsg(msg);
            instanceRepository.save(instance);
        });
        LogUtil.info("report task " + task.getId() + " stopped: visualization " + task.getRid() + " was deleted");
    }

    public void updateStatus(Long taskId, int status) {
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        transactionTemplate.executeWithoutResult(s -> queryFactory.update(t).set(t.status, status).where(t.id.eq(taskId)).execute());
    }

    private void validate(ReportCreator creator, boolean create) {
        if (StringUtils.isBlank(creator.getName()) || creator.getName().trim().length() > 50) {
            DEException.throwException(Translator.get("ee_report_name_invalid"));
        }
        if (StringUtils.isNotBlank(creator.getTitle()) && creator.getTitle().trim().length() > 50) {
            DEException.throwException(Translator.get("ee_report_title_invalid"));
        }
        if (creator.getRid() == null) {
            DEException.throwException(Translator.get("ee_report_resource_required"));
        }
        if (!permissionManage.current(resourceType(creator.getRtid()), creator.getRid()).satisfies(EeConstants.WEIGHT_READ)) {
            DEException.throwException(Translator.get("ee_report_resource_no_permission"));
        }
        if (StringUtils.isNotBlank(creator.getPixel())) {
            var matcher = PIXEL.matcher(creator.getPixel());
            if (!matcher.matches()) {
                DEException.throwException(Translator.get("ee_report_pixel_invalid"));
            }
            int w = Integer.parseInt(matcher.group(1));
            int h = Integer.parseInt(matcher.group(2));
            if (w <= 800 || w >= 10000 || h <= 500 || h >= 6250) {
                DEException.throwException(Translator.get("ee_report_pixel_invalid"));
            }
        }
        if (CollectionUtils.isEmpty(creator.getReciFlagList())) {
            DEException.throwException(Translator.get("ee_report_channel_required"));
        }
        if (CollectionUtils.isNotEmpty(creator.getEmailList())) {
            for (String email : creator.getEmailList()) {
                if (StringUtils.isNotBlank(email) && !EMAIL.matcher(email.trim()).matches()) {
                    DEException.throwException(Translator.get("ee_report_email_invalid", email));
                }
            }
        }
        boolean anyReci = CollectionUtils.isNotEmpty(creator.getUidList()) || CollectionUtils.isNotEmpty(creator.getRidList())
                || CollectionUtils.isNotEmpty(creator.getEmailList()) || CollectionUtils.isNotEmpty(creator.getDingtalkGroupList())
                || CollectionUtils.isNotEmpty(creator.getLarkGroupList()) || CollectionUtils.isNotEmpty(creator.getLarksuiteGroupList());
        if (!anyReci) {
            DEException.throwException(Translator.get("ee_report_recipient_required"));
        }
        if (creator.getStartTime() == null) {
            DEException.throwException(Translator.get("ee_report_start_required"));
        }
        if (create && creator.getStartTime() < System.currentTimeMillis() - 60_000L && creator.getEndTime() != null
                && creator.getEndTime() < System.currentTimeMillis()) {
            DEException.throwException(Translator.get("ee_report_end_invalid"));
        }
        if (creator.getEndTime() != null && creator.getEndTime() <= creator.getStartTime()) {
            DEException.throwException(Translator.get("ee_report_end_invalid"));
        }
        // 校验频率
        EeRateUtils.reportCron(creator.getRateType(), creator.getRateVal());
        if (Boolean.TRUE.equals(creator.getRetryEnable())) {
            if (creator.getRetryLimit() == null || creator.getRetryLimit() < 1 || creator.getRetryLimit() > 30
                    || creator.getRetryInterval() == null || creator.getRetryInterval() < 1 || creator.getRetryInterval() > 60) {
                DEException.throwException(Translator.get("ee_report_retry_invalid"));
            }
        }
    }

    private void fill(XpackReportTask task, ReportCreator creator) {
        task.setName(creator.getName().trim());
        task.setTitle(StringUtils.isBlank(creator.getTitle()) ? creator.getName().trim() : creator.getTitle().trim());
        task.setContent(StringUtils.defaultString(creator.getContent()));
        task.setRtid(ObjectUtils.defaultIfNull(creator.getRtid(), ReportConstants.RTID_DASHBOARD));
        task.setRid(creator.getRid());
        task.setShowWatermark(Boolean.TRUE.equals(creator.getShowWatermark()));
        task.setFormat(ObjectUtils.defaultIfNull(creator.getFormat(), ReportConstants.FORMAT_JPEG));
        task.setViewIds(ReportConstants.toJson(creator.getViewIdList()));
        task.setViewDataRange(ObjectUtils.defaultIfNull(creator.getViewDataRange(), 1));
        task.setPixel(StringUtils.defaultIfBlank(creator.getPixel(), "1920 * 1080"));
        task.setReciFlags(ReportConstants.toJson(creator.getReciFlagList()));
        task.setUids(ReportConstants.toJson(creator.getUidList()));
        task.setRids(ReportConstants.toJson(creator.getRidList()));
        task.setEmails(ReportConstants.toJson(cleanEmails(creator.getEmailList())));
        task.setDingtalkGroups(ReportConstants.toJson(creator.getDingtalkGroupList()));
        task.setLarkGroups(ReportConstants.toJson(creator.getLarkGroupList()));
        task.setLarksuiteGroups(ReportConstants.toJson(creator.getLarksuiteGroupList()));
        task.setExtWaitTime(clamp(creator.getExtWaitTime(), 0, 600, 0));
        task.setRenderTime(clamp(creator.getRenderTime(), 0, 30, 2));
        task.setRateType(ObjectUtils.defaultIfNull(creator.getRateType(), 0));
        task.setRateVal(creator.getRateVal());
        task.setStartTime(creator.getStartTime());
        task.setEndTime(creator.getEndTime());
        task.setRetryEnable(Boolean.TRUE.equals(creator.getRetryEnable()));
        task.setRetryLimit(ObjectUtils.defaultIfNull(creator.getRetryLimit(), 3));
        task.setRetryInterval(ObjectUtils.defaultIfNull(creator.getRetryInterval(), 5));
        task.setDataPermission(ObjectUtils.defaultIfNull(creator.getDataPermission(), 0));
    }

    private List<String> cleanEmails(List<String> emails) {
        if (emails == null) {
            return new ArrayList<>();
        }
        return emails.stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
    }

    private int clamp(Integer value, int min, int max, int def) {
        if (value == null) {
            return def;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void saveFilters(XpackReportTask task, List<VisualizationReportFilterVO> filters) {
        if (filters == null) {
            return;
        }
        reportFilterRepository.deleteByReportId(task.getId());
        long now = System.currentTimeMillis();
        String dvType = Objects.equals(task.getRtid(), ReportConstants.RTID_SCREEN) ? "dataV" : "dashboard";
        List<VisualizationReportFilter> records = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (VisualizationReportFilterVO vo : filters) {
            Long filterId = vo.getFilterId() != null ? vo.getFilterId() : vo.getId();
            if (filterId == null || !seen.add(filterId)) {
                continue;
            }
            VisualizationReportFilter record = new VisualizationReportFilter();
            record.setId(IDUtils.snowID());
            record.setReportId(task.getId());
            record.setTaskId(task.getId());
            record.setResourceId(task.getRid());
            record.setDvType(dvType);
            record.setComponentId(vo.getComponentId());
            record.setFilterId(filterId);
            record.setFilterInfo(vo.getFilterInfo());
            record.setFilterVersion(vo.getFilterVersion());
            record.setCreateTime(now);
            record.setCreateUser(String.valueOf(V3UserUtil.getUid()));
            records.add(record);
        }
        if (!records.isEmpty()) {
            reportFilterRepository.saveAll(records);
        }
    }

    public ReportInfoVO info(Long taskId) {
        XpackReportTask task = requireTask(taskId);
        ReportInfoVO vo = new ReportInfoVO();
        vo.setTaskId(task.getId());
        vo.setName(task.getName());
        vo.setTitle(task.getTitle());
        vo.setContent(task.getContent());
        vo.setRtid(task.getRtid());
        vo.setRid(task.getRid());
        vo.setShowWatermark(task.getShowWatermark());
        vo.setFormat(task.getFormat());
        vo.setViewIdList(ReportConstants.idStrings(task.getViewIds()));
        vo.setViewDataRange(task.getViewDataRange());
        vo.setPixel(task.getPixel());
        vo.setReciFlagList(ReportConstants.ints(task.getReciFlags()));
        vo.setUidList(ReportConstants.idStrings(task.getUids()));
        vo.setRidList(ReportConstants.idStrings(task.getRids()));
        vo.setEmailList(ReportConstants.strings(task.getEmails()));
        vo.setDingtalkGroupList(ReportConstants.strings(task.getDingtalkGroups()));
        vo.setLarkGroupList(ReportConstants.strings(task.getLarkGroups()));
        vo.setLarksuiteGroupList(ReportConstants.strings(task.getLarksuiteGroups()));
        vo.setExtWaitTime(task.getExtWaitTime());
        vo.setRenderTime(task.getRenderTime());
        vo.setRateType(task.getRateType());
        vo.setRateVal(task.getRateVal());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setRetryEnable(task.getRetryEnable());
        vo.setRetryLimit(task.getRetryLimit());
        vo.setRetryInterval(task.getRetryInterval());
        vo.setDataPermission(task.getDataPermission());
        return vo;
    }

    public void stop(Long taskId) {
        XpackReportTask task = requireTask(taskId);
        deTaskExecutor.removeTask(task.getId(), false);
        deTaskExecutor.removeRetryTask(task.getId());
        updateStatus(task.getId(), ReportConstants.STATUS_STOPPED);
    }

    public void start(Long taskId) {
        XpackReportTask task = requireTask(taskId);
        if (task.getEndTime() != null && task.getEndTime() < System.currentTimeMillis()) {
            updateStatus(task.getId(), ReportConstants.STATUS_FINISHED);
            return;
        }
        updateStatus(task.getId(), ReportConstants.STATUS_WAITING);
        task.setStatus(ReportConstants.STATUS_WAITING);
        schedule(task);
    }

    /**
     * 立即执行：存在定时任务时触发 Quartz；否则（已停止/已结束）注册一次性临时任务
     */
    public void fireNow(Long taskId) {
        XpackReportTask task = requireTask(taskId);
        if (Objects.equals(task.getStatus(), ReportConstants.STATUS_RUNNING)) {
            DEException.throwException(Translator.get("ee_report_task_running"));
        }
        try {
            if (!deTaskExecutor.fireNow(task.getId())) {
                deTaskExecutor.addTempTask(task.getId(), System.currentTimeMillis());
            }
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            DEException.throwException(e.getMessage());
        }
    }

    @Transactional
    public void delete(List<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return;
        }
        for (Long taskId : new LinkedHashSet<>(taskIds)) {
            XpackReportTask task = requireTask(taskId);
            deTaskExecutor.removeTask(task.getId(), false);
            deTaskExecutor.removeTask(task.getId(), true);
            deTaskExecutor.removeRetryTask(task.getId());
            QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
            queryFactory.delete(i).where(i.taskId.eq(task.getId())).execute();
            reportFilterRepository.deleteByReportId(task.getId());
            taskRepository.deleteById(task.getId());
        }
    }

    // ------------------------------------------------------------------ 执行记录

    public PageResult<ReportInstanceVO> logPager(int goPage, int pageSize, ReportInstanceRequest request) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        QXpackReportTask t = QXpackReportTask.xpackReportTask;
        QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
        BooleanBuilder where = visibleTask(t);
        if (request != null) {
            if (request.getTaskId() != null) {
                where.and(i.taskId.eq(request.getTaskId()));
            }
            if (StringUtils.isNotBlank(request.getKeyword())) {
                where.and(t.name.like("%" + request.getKeyword().trim() + "%"));
            }
            if (CollectionUtils.isNotEmpty(request.getExecStatusList())) {
                where.and(i.execStatus.in(request.getExecStatusList()));
            }
            List<Long> timeList = request.getTimeList();
            if (CollectionUtils.isNotEmpty(timeList) && timeList.size() >= 2 && timeList.get(0) != null && timeList.get(1) != null) {
                where.and(i.startTime.between(timeList.get(0), timeList.get(1)));
            }
        }
        JPAQuery<com.querydsl.core.Tuple> query = queryFactory.select(i.id, t.name, i.startTime, i.execStatus)
                .from(i).innerJoin(t).on(t.id.eq(i.taskId)).where(where);
        long total = query.clone().fetchCount();
        boolean desc = request == null || request.getTimeDesc() == null || request.getTimeDesc();
        query.orderBy(desc ? i.startTime.desc() : i.startTime.asc());
        List<ReportInstanceVO> records = query.offset((long) (page - 1) * size).limit(size).fetch().stream().map(tuple -> {
            ReportInstanceVO vo = new ReportInstanceVO();
            vo.setId(tuple.get(i.id));
            vo.setName(tuple.get(t.name));
            vo.setStartTime(tuple.get(i.startTime));
            vo.setExecStatus(tuple.get(i.execStatus));
            return vo;
        }).toList();
        return new PageResult<>(records, total, PageRequest.of(page - 1, size));
    }

    /**
     * 删除执行记录：指定 instanceId 删除单条；否则按 timeFlag（1 一天 2 一周 3 一月 4 三月 5 半年 6 一年）清理更早的记录
     */
    @Transactional
    public void deleteInstance(ReportInstanceDelRequest request) {
        QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
        if (request.getInstanceId() != null) {
            XpackReportInstance instance = instanceRepository.findById(request.getInstanceId()).orElse(null);
            if (instance == null) {
                return;
            }
            requireTask(instance.getTaskId());
            if (Objects.equals(instance.getExecStatus(), ReportConstants.EXEC_RUNNING)) {
                DEException.throwException(Translator.get("ee_report_instance_running"));
            }
            instanceRepository.deleteById(instance.getId());
            return;
        }
        long period = switch (ObjectUtils.defaultIfNull(request.getTimeFlag(), 1)) {
            case 2 -> 7 * DAY;
            case 3 -> 30 * DAY;
            case 4 -> 90 * DAY;
            case 5 -> 180 * DAY;
            case 6 -> 365 * DAY;
            default -> DAY;
        };
        long cutoff = System.currentTimeMillis() - period;
        List<Long> taskIds;
        if (request.getTaskId() != null) {
            taskIds = List.of(requireTask(request.getTaskId()).getId());
        } else {
            QXpackReportTask t = QXpackReportTask.xpackReportTask;
            taskIds = queryFactory.select(t.id).from(t).where(visibleTask(t)).fetch();
        }
        if (taskIds.isEmpty()) {
            return;
        }
        for (List<Long> part : partition(taskIds)) {
            queryFactory.delete(i).where(i.taskId.in(part), i.startTime.lt(cutoff), i.execStatus.ne(ReportConstants.EXEC_RUNNING)).execute();
        }
    }

    private List<List<Long>> partition(List<Long> ids) {
        List<List<Long>> result = new ArrayList<>();
        for (int k = 0; k < ids.size(); k += 500) {
            result.add(ids.subList(k, Math.min(ids.size(), k + 500)));
        }
        return result;
    }

    public String logMsg(ReportInstanceMsgRequest request) {
        XpackReportInstance instance = null;
        if (request.getInstanceId() != null) {
            instance = instanceRepository.findById(request.getInstanceId()).orElse(null);
            if (instance != null) {
                requireTask(instance.getTaskId());
            }
        } else if (request.getTaskId() != null) {
            requireTask(request.getTaskId());
            QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
            instance = queryFactory.selectFrom(i).where(i.taskId.eq(request.getTaskId()))
                    .orderBy(i.startTime.desc()).limit(1).fetchFirst();
        }
        return instance == null ? null : instance.getMsg();
    }
}
