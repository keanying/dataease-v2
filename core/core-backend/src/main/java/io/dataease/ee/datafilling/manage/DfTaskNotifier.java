package io.dataease.ee.datafilling.manage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.ee.datafilling.dao.entity.DfTask;
import io.dataease.ee.platform.message.EePlatformMessageSender;
import io.dataease.ee.report.notify.EeNotifyChannel;
import io.dataease.ee.report.notify.EeNotifyDispatcher;
import io.dataease.ee.report.notify.EeNotifyMessage;
import io.dataease.ee.report.notify.EeNotifyResult;
import io.dataease.ee.report.notify.EeNotifyTypes;
import io.dataease.ee.report.notify.EeRecipients;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 填报任务下发后的外部消息通知（邮件、企业微信、钉钉、飞书、国际飞书），复用定时报告的 {@link EeNotifyDispatcher}。
 * <p>站内消息（reciFlag 0）由 websocket 推送，不在此处理。发送在后台线程中进行，结果（渠道失败、未绑定账号等）
 * 通过回调写入子任务日志，不影响下发结果。</p>
 */
@Component
public class DfTaskNotifier {

    /**
     * 富文本中的占位符：{@code <span id="changeText-N" ...><span ...>[字段]</span></span>}
     */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "<span[^>]*\\bid=\"changeText-(\\d+)\"[^>]*>(?:\\s*<span[^>]*>[^<]*</span>\\s*|[^<]*)</span>", Pattern.DOTALL);

    private static final String FILL_PAGE = "#/msg/msg-fill";

    private final ObjectProvider<EeNotifyDispatcher> dispatcherProvider;

    private final ObjectProvider<EePlatformMessageSender> senderProvider;

    private final ExecutorService executor;

    /**
     * 前端访问地址（如 https://bi.example.com/），配置后消息正文附带“前往填报”链接
     */
    @Value("${dataease.data-filling.base-url:}")
    private String baseUrl;

    public DfTaskNotifier(ObjectProvider<EeNotifyDispatcher> dispatcherProvider, ObjectProvider<EePlatformMessageSender> senderProvider) {
        this.dispatcherProvider = dispatcherProvider;
        this.senderProvider = senderProvider;
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), r -> {
            Thread t = new Thread(r, "df-notify-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 需要外部渠道发送时异步发送
     *
     * @param task      任务
     * @param end       本次填报截止时间（可为空）
     * @param receivers 本次下发的用户
     * @param onResult  发送结束后的回调，参数为需要记录的提示（无问题时为空串）
     */
    public void notifyAsync(DfTask task, Long end, Collection<Long> receivers, Consumer<String> onResult) {
        List<Integer> flags = externalFlags(task);
        if (flags.isEmpty() || receivers == null || receivers.isEmpty()) {
            return;
        }
        List<Long> uids = new ArrayList<>(receivers);
        Locale locale = LocaleContextHolder.getLocale();
        try {
            executor.execute(() -> {
                LocaleContextHolder.setLocale(locale);
                String summary;
                try {
                    summary = send(task, end, uids, flags);
                } catch (Throwable e) {
                    LogUtil.error("data filling notify failed: " + e.getMessage(), e);
                    summary = Translator.get("ee_df_notify_failed", EeNotifyDispatcher.rootMessage(e));
                }
                try {
                    onResult.accept(summary);
                } catch (Exception e) {
                    LogUtil.warn("data filling notify result not recorded: " + e.getMessage());
                } finally {
                    LocaleContextHolder.resetLocaleContext();
                }
            });
        } catch (Exception e) {
            LogUtil.warn("data filling notify rejected: " + e.getMessage());
            onResult.accept(Translator.get("ee_df_notify_failed", EeNotifyDispatcher.rootMessage(e)));
        }
    }

    /**
     * 外部渠道（1 邮件 2 企业微信 3 钉钉 4 飞书 5 国际飞书）
     */
    static List<Integer> externalFlags(DfTask task) {
        List<Integer> flags = DfJson.read(task.getReciFlagList(), new TypeReference<List<Integer>>() {
        });
        List<Integer> result = new ArrayList<>();
        if (flags != null) {
            for (Integer f : new LinkedHashSet<>(flags)) {
                if (EeNotifyTypes.ofReciFlag(f) != null) {
                    result.add(f);
                }
            }
        }
        return result;
    }

    private String send(DfTask task, Long end, List<Long> uids, List<Integer> flags) {
        V3UserUtil.setContext(task.getCreateBy(), task.getOrgId());
        try {
            EeNotifyDispatcher dispatcher = dispatcherProvider.getObject();
            List<String> notes = new ArrayList<>();
            List<Integer> usable = new ArrayList<>();
            Map<String, EeNotifyChannel> channels = dispatcher.channels();
            for (Integer flag : flags) {
                String type = EeNotifyTypes.ofReciFlag(flag);
                if (available(type, channels)) {
                    usable.add(flag);
                } else {
                    notes.add(Translator.get("ee_report_notify_channel_unavailable", type));
                }
            }
            int success = 0;
            if (!usable.isEmpty()) {
                EeRecipients recipients = new EeRecipients();
                recipients.setReciFlags(usable);
                recipients.setUids(uids);
                EeNotifyMessage msg = new EeNotifyMessage();
                msg.setTitle(title(task));
                msg.setContent(content(task, end));
                msg.setSource("data_filling");
                msg.setSourceId(task.getId());
                msg.setOid(task.getOrgId());
                EeNotifyResult result = dispatcher.dispatch(msg, recipients, false);
                success = result.getSuccessCount();
                if (StringUtils.isNotBlank(result.summary())) {
                    notes.add(result.summary());
                }
            }
            String summary = String.join("\n", notes);
            LogUtil.info("data filling task " + task.getId() + " notify: success channels " + success
                    + (summary.isEmpty() ? "" : ", " + summary.replace('\n', ' ')));
            return summary;
        } finally {
            V3UserUtil.clear();
        }
    }

    /**
     * 渠道是否可用：邮件看渠道是否存在（未配置邮箱时发送会报错并记录）；企业微信、钉钉、飞书需对应平台已配置并启用
     */
    private boolean available(String type, Map<String, EeNotifyChannel> channels) {
        if (type == null || !channels.containsKey(type)) {
            return false;
        }
        Integer origin = EeNotifyTypes.platformOrigin(type);
        if (origin == null) {
            return true;
        }
        try {
            return senderProvider.orderedStream().anyMatch(s -> s.origin() == origin && s.available());
        } catch (Exception e) {
            return false;
        }
    }

    String title(DfTask task) {
        String title = StringUtils.trimToNull(task.getMsgTitle());
        return title == null ? Translator.get("ee_df_notify_default_title") : title;
    }

    /**
     * 正文：自定义或前端默认模板中的占位符替换为任务名称与截止时间；没有模板时使用默认文案
     */
    String content(DfTask task, Long end) {
        String name = StringUtils.defaultString(task.getName());
        String deadline = end == null ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(end));
        String template = task.getMsgContent();
        String html;
        if (StringUtils.isBlank(template)) {
            html = "<p>" + Translator.get("ee_df_notify_default_content", HtmlUtils.htmlEscape(name), deadline) + "</p>";
        } else {
            html = replacePlaceholders(template, name, deadline);
        }
        if (StringUtils.isNotBlank(baseUrl) && !StringUtils.startsWith(baseUrl.trim(), "${")) {
            String link = StringUtils.removeEnd(baseUrl.trim(), "/") + "/" + FILL_PAGE;
            html += "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">" + Translator.get("ee_df_notify_link") + "</a></p>";
        }
        return html;
    }

    static String replacePlaceholders(String template, String name, String deadline) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "0" -> HtmlUtils.htmlEscape(name);
                case "1" -> deadline;
                default -> m.group();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
