package io.dataease.ee.report;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.extensions.view.dto.ChartExtRequest;
import io.dataease.chart.constant.ChartConstants;
import io.dataease.chart.manage.ChartDataManage;
import io.dataease.chart.manage.ChartViewManege;
import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.ee.auth.EeTokenManage;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.report.dao.QXpackReportInstance;
import io.dataease.ee.report.dao.QXpackReportTask;
import io.dataease.ee.report.dao.XpackReportInstance;
import io.dataease.ee.report.dao.XpackReportInstanceRepository;
import io.dataease.ee.report.dao.XpackReportTask;
import io.dataease.ee.report.dao.XpackReportTaskRepository;
import io.dataease.ee.report.notify.*;
import io.dataease.ee.report.render.EeScreenshotClient;
import io.dataease.ee.report.render.ReportFileUtils;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.i18n.Translator;
import io.dataease.job.schedule.DeTaskExecutor;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoRepository;
import io.dataease.websocket.WsMessage;
import io.dataease.websocket.WsService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行定时报告：以创建人身份截图仪表板/大屏、导出图表数据，并按接收人配置发送。
 *
 * <p>dataPermission=1 时按接收人分别渲染（内部用户各自以自己的身份渲染并单独发送），
 * 额外邮箱与群仍使用创建人身份渲染的版本。</p>
 */
@Component
public class ReportRunner {

    private static final Pattern PIXEL = Pattern.compile("^\\s*(\\d+)\\s*\\*\\s*(\\d+)\\s*$");

    private static final String IMAGE_CID = "de-report-image";

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    private final XpackReportTaskRepository taskRepository;

    private final XpackReportInstanceRepository instanceRepository;

    private final JPAQueryFactory queryFactory;

    private final TransactionTemplate transactionTemplate;

    private final EePermissionManage permissionManage;

    private final EeTokenManage tokenManage;

    private final EeScreenshotClient screenshotClient;

    private final EeNotifyDispatcher dispatcher;

    private final DataVisualizationInfoRepository visualizationRepository;

    private final ChartViewManege chartViewManege;

    private final ChartDataManage chartDataManage;

    private final DeTaskExecutor deTaskExecutor;

    private final ObjectProvider<WsService> wsServiceProvider;

    public ReportRunner(XpackReportTaskRepository taskRepository, XpackReportInstanceRepository instanceRepository,
                        JPAQueryFactory queryFactory, TransactionTemplate transactionTemplate,
                        EePermissionManage permissionManage, EeTokenManage tokenManage,
                        EeScreenshotClient screenshotClient, EeNotifyDispatcher dispatcher,
                        DataVisualizationInfoRepository visualizationRepository,
                        @Lazy ChartViewManege chartViewManege, @Lazy ChartDataManage chartDataManage,
                        @Lazy DeTaskExecutor deTaskExecutor, ObjectProvider<WsService> wsServiceProvider) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.queryFactory = queryFactory;
        this.transactionTemplate = transactionTemplate;
        this.permissionManage = permissionManage;
        this.tokenManage = tokenManage;
        this.screenshotClient = screenshotClient;
        this.dispatcher = dispatcher;
        this.visualizationRepository = visualizationRepository;
        this.chartViewManege = chartViewManege;
        this.chartDataManage = chartDataManage;
        this.deTaskExecutor = deTaskExecutor;
        this.wsServiceProvider = wsServiceProvider;
    }

    /**
     * 执行一次报告任务
     *
     * @return 任务是否存在（不存在时调用方移除 Quartz 任务）
     */
    public boolean run(Long taskId, int triggerType) {
        XpackReportTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return false;
        }
        if (triggerType == ReportConstants.TRIGGER_SCHEDULE && Objects.equals(task.getStatus(), ReportConstants.STATUS_STOPPED)) {
            return false;
        }
        if (!running.add(taskId)) {
            LogUtil.info("report task " + taskId + " is running, skip this trigger");
            return true;
        }
        XpackReportInstance instance = new XpackReportInstance();
        ReportConstants.applyLocale(task.getCreator());
        try {
            long now = System.currentTimeMillis();
            instance.setId(IDUtils.snowID());
            instance.setTaskId(taskId);
            instance.setStartTime(now);
            instance.setExecStatus(ReportConstants.EXEC_RUNNING);
            instance.setTriggerType(triggerType);
            instance.setOid(task.getOid());
            instanceRepository.save(instance);
            markStart(taskId, now);
            notice(task, instance.getId());

            Outcome outcome;
            try {
                outcome = execute(task);
            } catch (Throwable e) {
                LogUtil.error("report task " + taskId + " failed: " + e.getMessage(), e);
                outcome = new Outcome(false, true, Translator.get("ee_report_exec_error") + "\n" + ExceptionUtils.getStackTrace(e));
            }
            finish(task, instance, outcome, triggerType);
        } finally {
            V3UserUtil.clear();
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            running.remove(taskId);
            notice(task, instance.getId());
        }
        return true;
    }

    public boolean isRunning(Long taskId) {
        return running.contains(taskId);
    }

    private void markStart(Long taskId, long now) {
        transactionTemplate.executeWithoutResult(status -> {
            QXpackReportTask t = QXpackReportTask.xpackReportTask;
            queryFactory.update(t).set(t.lastExecTime, now).set(t.lastExecStatus, ReportConstants.EXEC_RUNNING)
                    .where(t.id.eq(taskId)).execute();
            queryFactory.update(t).set(t.status, ReportConstants.STATUS_RUNNING)
                    .where(t.id.eq(taskId), t.status.in(ReportConstants.STATUS_WAITING, ReportConstants.STATUS_FINISHED)).execute();
        });
    }

    private void finish(XpackReportTask task, XpackReportInstance instance, Outcome outcome, int triggerType) {
        int execStatus = outcome.success() ? ReportConstants.EXEC_SUCCESS : ReportConstants.EXEC_FAILED;
        long end = System.currentTimeMillis();
        String msg = StringUtils.defaultIfBlank(outcome.message(), null);
        int nextStatus = expired(task) ? ReportConstants.STATUS_FINISHED : ReportConstants.STATUS_WAITING;
        transactionTemplate.executeWithoutResult(status -> {
            QXpackReportInstance i = QXpackReportInstance.xpackReportInstance;
            queryFactory.update(i).set(i.execStatus, execStatus).set(i.endTime, end)
                    .set(i.msg, msg == null ? "" : msg).where(i.id.eq(instance.getId())).execute();
            QXpackReportTask t = QXpackReportTask.xpackReportTask;
            queryFactory.update(t).set(t.lastExecStatus, execStatus).where(t.id.eq(task.getId())).execute();
            queryFactory.update(t).set(t.status, nextStatus)
                    .where(t.id.eq(task.getId()), t.status.eq(ReportConstants.STATUS_RUNNING)).execute();
        });
        try {
            if (outcome.success() && triggerType == ReportConstants.TRIGGER_RETRY) {
                deTaskExecutor.removeRetryTask(task.getId());
            } else if (!outcome.success() && outcome.retryable() && triggerType != ReportConstants.TRIGGER_RETRY
                    && Boolean.TRUE.equals(task.getRetryEnable())) {
                deTaskExecutor.addRetryTask(task.getId(), ObjectUtils.defaultIfNull(task.getRetryLimit(), 3),
                        ObjectUtils.defaultIfNull(task.getRetryInterval(), 5), null);
            }
        } catch (Exception e) {
            LogUtil.error("schedule retry of report task " + task.getId() + " failed: " + e.getMessage(), e);
        }
    }

    private boolean expired(XpackReportTask task) {
        if (task.getEndTime() == null) {
            return false;
        }
        if (task.getEndTime() < System.currentTimeMillis()) {
            return true;
        }
        try {
            return EeRateUtils.nextFireTime(EeRateUtils.reportCron(task.getRateType(), task.getRateVal()),
                    task.getStartTime(), task.getEndTime()) == null;
        } catch (Exception e) {
            return false;
        }
    }

    private void notice(XpackReportTask task, Long instanceId) {
        if (task.getCreator() == null || instanceId == null) {
            return;
        }
        try {
            WsService wsService = wsServiceProvider.getIfAvailable();
            if (wsService == null) {
                return;
            }
            Map<String, String> data = new HashMap<>();
            data.put("taskId", String.valueOf(task.getId()));
            data.put("instanceId", String.valueOf(instanceId));
            wsService.releaseMessage(new WsMessage<>(task.getCreator(), ReportConstants.WS_TOPIC, data));
        } catch (Exception e) {
            LogUtil.error("report notice failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 执行

    private Outcome execute(XpackReportTask task) {
        V3BaseUser creator = V3UserUtil.getUser(task.getCreator());
        if (creator == null || !Boolean.TRUE.equals(creator.getEnable())) {
            return new Outcome(false, false, Translator.get("ee_report_creator_invalid"));
        }
        DataVisualizationInfo dv = task.getRid() == null ? null : visualizationRepository.findById(task.getRid()).orElse(null);
        if (dv == null || Boolean.TRUE.equals(dv.getDeleteFlag())) {
            return new Outcome(false, false, Translator.get("ee_report_resource_not_exist"));
        }
        EeRecipients recipients = recipients(task);
        List<String> lines = new ArrayList<>();
        boolean renderOk = true;
        boolean anySent = false;
        boolean sendOk = true;

        if (Objects.equals(task.getDataPermission(), 1)) {
            // 按接收人分别渲染
            for (Long uid : dispatcher.resolveUserIds(recipients)) {
                V3BaseUser user = V3UserUtil.getUser(uid);
                String userName = user == null ? String.valueOf(uid) : user.getName();
                V3UserUtil.setContext(uid, task.getOid());
                if (!canRead(uid, task)) {
                    lines.add(Translator.get("ee_report_user_no_permission", userName));
                    continue;
                }
                Rendered rendered = render(task, dv, uid);
                renderOk &= rendered.errors.isEmpty();
                rendered.errors.forEach(err -> lines.add("[" + userName + "] " + err));
                EeRecipients single = new EeRecipients();
                single.setReciFlags(recipients.getReciFlags());
                single.setUids(List.of(uid));
                EeNotifyResult result = dispatcher.dispatch(message(task, rendered), single, false);
                anySent |= result.getSuccessCount() > 0;
                sendOk &= result.isSuccess();
                if (StringUtils.isNotBlank(result.summary())) {
                    lines.add("[" + userName + "] " + result.summary());
                }
            }
            boolean hasCommon = CollectionUtils.isNotEmpty(recipients.getEmails()) || CollectionUtils.isNotEmpty(recipients.getDingtalkGroups())
                    || CollectionUtils.isNotEmpty(recipients.getLarkGroups()) || CollectionUtils.isNotEmpty(recipients.getLarksuiteGroups());
            if (hasCommon) {
                V3UserUtil.setContext(task.getCreator(), task.getOid());
                if (!canRead(task.getCreator(), task)) {
                    return new Outcome(false, false, Translator.get("ee_report_resource_no_permission"));
                }
                Rendered rendered = render(task, dv, task.getCreator());
                renderOk &= rendered.errors.isEmpty();
                lines.addAll(rendered.errors);
                EeRecipients common = new EeRecipients();
                common.setEmails(recipients.getEmails());
                common.setDingtalkGroups(recipients.getDingtalkGroups());
                common.setLarkGroups(recipients.getLarkGroups());
                common.setLarksuiteGroups(recipients.getLarksuiteGroups());
                EeNotifyResult result = dispatcher.dispatch(message(task, rendered), common, true);
                anySent |= result.getSuccessCount() > 0;
                sendOk &= result.isSuccess();
                if (StringUtils.isNotBlank(result.summary())) {
                    lines.add(result.summary());
                }
            }
            if (!anySent && lines.isEmpty()) {
                lines.add(Translator.get("ee_report_notify_no_target"));
                sendOk = false;
            }
        } else {
            V3UserUtil.setContext(task.getCreator(), task.getOid());
            if (!canRead(task.getCreator(), task)) {
                return new Outcome(false, false, Translator.get("ee_report_resource_no_permission"));
            }
            Rendered rendered = render(task, dv, task.getCreator());
            renderOk = rendered.errors.isEmpty();
            lines.addAll(rendered.errors);
            EeNotifyResult result = dispatcher.dispatch(message(task, rendered), recipients, true);
            anySent = result.getSuccessCount() > 0;
            sendOk = result.isSuccess();
            if (StringUtils.isNotBlank(result.summary())) {
                lines.add(result.summary());
            }
        }
        if (!renderOk && anySent) {
            lines.add(Translator.get("ee_report_partial_sent"));
        }
        boolean success = renderOk && sendOk && anySent;
        // 已经发出的报告不再重试，避免重复发送
        boolean retryable = !success && !anySent;
        return new Outcome(success, retryable, String.join("\n", lines));
    }

    private boolean canRead(Long uid, XpackReportTask task) {
        return permissionManage.effective(uid, task.getOid(), ReportManage.resourceType(task.getRtid()), task.getRid())
                .satisfies(EeConstants.WEIGHT_READ);
    }

    private EeRecipients recipients(XpackReportTask task) {
        EeRecipients recipients = new EeRecipients();
        recipients.setReciFlags(ReportConstants.ints(task.getReciFlags()));
        recipients.setUids(ReportConstants.longs(task.getUids()));
        recipients.setRids(ReportConstants.longs(task.getRids()));
        recipients.setEmails(ReportConstants.strings(task.getEmails()));
        recipients.setDingtalkGroups(ReportConstants.strings(task.getDingtalkGroups()));
        recipients.setLarkGroups(ReportConstants.strings(task.getLarkGroups()));
        recipients.setLarksuiteGroups(ReportConstants.strings(task.getLarksuiteGroups()));
        return recipients;
    }

    private EeNotifyMessage message(XpackReportTask task, Rendered rendered) {
        EeNotifyMessage msg = new EeNotifyMessage();
        msg.setTitle(StringUtils.defaultIfBlank(task.getTitle(), task.getName()));
        msg.setContent(StringUtils.defaultString(task.getContent()));
        msg.setAttachments(rendered.attachments);
        msg.setSource("report");
        msg.setSourceId(task.getId());
        msg.setOid(task.getOid());
        return msg;
    }

    /**
     * 以当前线程身份（uid）渲染报告内容
     */
    private Rendered render(XpackReportTask task, DataVisualizationInfo dv, Long uid) {
        Rendered rendered = new Rendered();
        String fileBase = StringUtils.defaultIfBlank(task.getTitle(), task.getName()).replaceAll("[\\\\/:*?\"<>|]", "_");
        boolean pdf = Objects.equals(task.getFormat(), ReportConstants.FORMAT_PDF);

        // 1. 截图
        String token = null;
        try {
            int width = 1920;
            int height = 1080;
            Matcher matcher = PIXEL.matcher(StringUtils.defaultString(task.getPixel()));
            if (matcher.matches()) {
                width = Integer.parseInt(matcher.group(1));
                height = Integer.parseInt(matcher.group(2));
            }
            if (!screenshotClient.configured()) {
                rendered.errors.add(Translator.get("ee_report_screenshot_not_config"));
            } else {
                token = tokenManage.generate(uid, task.getOid()).getToken();
                String dvType = StringUtils.defaultIfBlank(dv.getType(), Objects.equals(task.getRtid(), ReportConstants.RTID_SCREEN) ? "dataV" : "dashboard");
                byte[] file = screenshotClient.capture(new EeScreenshotClient.ScreenshotRequest(
                        screenshotClient.previewUrl(task.getRid(), dvType, task.getId(), Boolean.TRUE.equals(task.getShowWatermark())),
                        token, uid, task.getOid(), pdf, width, height,
                        ObjectUtils.defaultIfNull(task.getExtWaitTime(), 0), ObjectUtils.defaultIfNull(task.getRenderTime(), 2)));
                if (pdf) {
                    byte[] data = ReportFileUtils.isPdf(file) ? file : ReportFileUtils.imageToPdf(file);
                    rendered.attachments.add(new EeNotifyAttachment(fileBase + ".pdf", "application/pdf", data, false, null));
                } else {
                    rendered.attachments.add(new EeNotifyAttachment(fileBase + ".jpeg", "image/jpeg", file, true, IMAGE_CID));
                }
            }
        } catch (Exception e) {
            LogUtil.error("report screenshot failed: " + e.getMessage(), e);
            rendered.errors.add(EeNotifyDispatcher.rootMessage(e));
        } finally {
            if (token != null) {
                tokenManage.destroy(token);
            }
        }

        // 2. 图表数据
        List<Long> viewIds = ReportConstants.longs(task.getViewIds());
        if (!viewIds.isEmpty()) {
            List<ReportFileUtils.SheetData> sheets = new ArrayList<>();
            for (Long viewId : viewIds) {
                try {
                    ReportFileUtils.SheetData sheet = chartSheet(task, viewId);
                    if (sheet != null) {
                        sheets.add(sheet);
                    }
                } catch (Exception e) {
                    LogUtil.error("report chart data failed: " + e.getMessage(), e);
                    rendered.errors.add(Translator.get("ee_report_chart_data_failed", String.valueOf(viewId), EeNotifyDispatcher.rootMessage(e)));
                }
            }
            if (!sheets.isEmpty()) {
                try {
                    rendered.attachments.add(new EeNotifyAttachment(fileBase + ".xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ReportFileUtils.excel(sheets), false, null));
                } catch (Exception e) {
                    rendered.errors.add(Translator.get("ee_report_excel_failed", EeNotifyDispatcher.rootMessage(e)));
                }
            }
        }
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private ReportFileUtils.SheetData chartSheet(XpackReportTask task, Long viewId) throws Exception {
        ChartViewDTO view = chartViewManege.getDetails(viewId, "core");
        if (view == null || !Objects.equals(view.getSceneId(), task.getRid())) {
            throw new IllegalStateException(Translator.get("ee_report_chart_not_exist"));
        }
        ChartExtRequest extRequest = new ChartExtRequest();
        extRequest.setGoPage(1L);
        extRequest.setPageSize(50000L);
        extRequest.setFilter(new ArrayList<>());
        if (Objects.equals(task.getViewDataRange(), 0)) {
            // 全部数据
            view.setResultMode(ChartConstants.VIEW_RESULT_MODE.ALL);
            extRequest.setResultMode(ChartConstants.VIEW_RESULT_MODE.ALL);
        }
        view.setChartExtRequest(extRequest);
        ChartViewDTO result = chartDataManage.calcData(view);
        Map<String, Object> data = result == null ? null : result.getData();
        if (MapUtils.isEmpty(data) || !(data.get("fields") instanceof List<?> fields)) {
            throw new IllegalStateException(Translator.get("ee_report_chart_unsupported", String.valueOf(view.getType())));
        }
        Object rows = data.get("tableRow");
        List<Map<String, Object>> tableRow = rows instanceof List<?> ? (List<Map<String, Object>>) rows : new ArrayList<>();
        return new ReportFileUtils.SheetData(StringUtils.defaultIfBlank(view.getTitle(), String.valueOf(viewId)), fields, tableRow);
    }

    private static class Rendered {
        final List<EeNotifyAttachment> attachments = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private record Outcome(boolean success, boolean retryable, String message) {
    }
}
