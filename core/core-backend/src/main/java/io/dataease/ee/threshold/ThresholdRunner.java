package io.dataease.ee.threshold;

import io.dataease.api.chart.request.ThresholdCheckRequest;
import io.dataease.api.chart.vo.ThresholdCheckVO;
import io.dataease.chart.manage.ChartViewThresholdManage;
import io.dataease.ee.report.ReportConstants;
import io.dataease.ee.report.notify.EeNotifyDispatcher;
import io.dataease.ee.report.notify.EeNotifyMessage;
import io.dataease.ee.report.notify.EeNotifyResult;
import io.dataease.ee.report.notify.EeRecipients;
import io.dataease.ee.threshold.dao.XpackThresholdInfo;
import io.dataease.ee.threshold.dao.XpackThresholdInfoRepository;
import io.dataease.ee.threshold.dao.XpackThresholdInstance;
import io.dataease.ee.threshold.dao.XpackThresholdInstanceRepository;
import io.dataease.i18n.Translator;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行阈值告警检测：以告警创建人身份通过核心 {@link ChartViewThresholdManage#checkThreshold} 取数并匹配规则，
 * 触发时按接收人配置发送消息，并记录检测结果。
 */
@Component
public class ThresholdRunner {

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    private final XpackThresholdInfoRepository coreRepository;

    private final XpackThresholdInstanceRepository instanceRepository;

    private final ThresholdManage thresholdManage;

    private final ChartViewThresholdManage chartViewThresholdManage;

    private final EeNotifyDispatcher dispatcher;

    public ThresholdRunner(XpackThresholdInfoRepository coreRepository, XpackThresholdInstanceRepository instanceRepository,
                           ThresholdManage thresholdManage, @Lazy ChartViewThresholdManage chartViewThresholdManage,
                           EeNotifyDispatcher dispatcher) {
        this.coreRepository = coreRepository;
        this.instanceRepository = instanceRepository;
        this.thresholdManage = thresholdManage;
        this.chartViewThresholdManage = chartViewThresholdManage;
        this.dispatcher = dispatcher;
    }

    /**
     * @return 告警是否存在且启用（否则调用方移除检测任务）
     */
    public boolean run(Long thresholdId) {
        XpackThresholdInfo info = thresholdManage.findCore(thresholdId);
        if (info == null || !Boolean.TRUE.equals(info.getEnable())) {
            return false;
        }
        if (!running.add(thresholdId)) {
            return true;
        }
        ReportConstants.applyLocale(info.getCreator());
        try {
            check(info);
        } finally {
            V3UserUtil.clear();
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            running.remove(thresholdId);
        }
        return true;
    }

    private void check(XpackThresholdInfo info) {
        long now = System.currentTimeMillis();
        XpackThresholdInstance instance = new XpackThresholdInstance();
        instance.setId(IDUtils.snowID());
        instance.setThresholdId(info.getId());
        instance.setExecTime(now);
        instance.setOid(info.getOid());
        instance.setStatus(false);
        boolean previous = Boolean.TRUE.equals(info.getStatus());
        boolean triggered = false;
        try {
            V3BaseUser creator = V3UserUtil.getUser(info.getCreator());
            if (creator == null || !Boolean.TRUE.equals(creator.getEnable())) {
                instance.setMsg(Translator.get("ee_report_creator_invalid"));
            } else {
                V3UserUtil.setContext(info.getCreator(), info.getOid());
                ThresholdCheckRequest request = new ThresholdCheckRequest();
                request.setChartId(info.getChartId());
                request.setThresholdRules(info.getThresholdRules());
                request.setThresholdTemplate(StringUtils.defaultString(info.getMsgContent()));
                request.setResourceTable(ThresholdManage.CORE);
                request.setShowFieldValue(Boolean.TRUE.equals(info.getShowFieldValue()));
                request.setThresholdLimit(thresholdManage.thresholdLimit());
                ThresholdCheckVO vo = chartViewThresholdManage.checkThreshold(request);
                if (vo != null && StringUtils.isNotBlank(vo.getMsg())) {
                    instance.setMsg(vo.getMsg());
                } else if (vo != null && vo.isValid()) {
                    triggered = true;
                    instance.setStatus(true);
                    instance.setContent(vo.getContent());
                    if (Boolean.TRUE.equals(info.getRepeatSend()) || !previous) {
                        EeNotifyResult result = dispatcher.dispatch(message(info, vo.getContent()), recipients(info), true);
                        if (StringUtils.isNotBlank(result.summary())) {
                            instance.setMsg(result.summary());
                        }
                    } else {
                        instance.setMsg(Translator.get("ee_threshold_repeat_skip"));
                    }
                }
            }
        } catch (Throwable e) {
            LogUtil.error("threshold " + info.getId() + " check failed: " + e.getMessage(), e);
            instance.setMsg(ExceptionUtils.getStackTrace(e));
        }
        instanceRepository.save(instance);
        XpackThresholdInfo latest = coreRepository.findById(info.getId()).orElse(null);
        if (latest != null) {
            latest.setStatus(triggered);
            latest.setLastExecTime(now);
            coreRepository.save(latest);
        }
    }

    private EeNotifyMessage message(XpackThresholdInfo info, String content) {
        EeNotifyMessage msg = new EeNotifyMessage();
        msg.setTitle(StringUtils.isBlank(info.getMsgTitle()) ? Translator.get("ee_threshold_default_title") : info.getMsgTitle());
        msg.setContent(content);
        msg.setSource("threshold");
        msg.setSourceId(info.getId());
        msg.setOid(info.getOid());
        return msg;
    }

    private EeRecipients recipients(XpackThresholdInfo info) {
        EeRecipients recipients = new EeRecipients();
        recipients.setReciFlags(ReportConstants.ints(info.getReciFlags()));
        recipients.setUids(ReportConstants.longs(info.getUids()));
        recipients.setRids(ReportConstants.longs(info.getRids()));
        recipients.setEmails(ReportConstants.strings(info.getEmails()));
        recipients.setLarkGroups(ReportConstants.strings(info.getLarkGroups()));
        recipients.setLarksuiteGroups(ReportConstants.strings(info.getLarksuiteGroups()));
        recipients.setWebhooks(ReportConstants.strings(info.getWebhooks()));
        return recipients;
    }
}
