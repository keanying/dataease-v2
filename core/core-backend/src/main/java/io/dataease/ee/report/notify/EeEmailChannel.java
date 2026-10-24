package io.dataease.ee.report.notify;

import io.dataease.ee.setting.email.EeMailAttachment;
import io.dataease.ee.setting.email.EeMailSender;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件渠道：委托系统设置中的 {@link EeMailSender}（core_sys_setting 的 email.* 配置）发送 HTML 邮件。
 *
 * <p>内嵌图片以 {@code cid:<contentId>} 引用（正文未引用的内嵌图片会追加到正文末尾），其余附件作为普通附件。</p>
 */
@Component
public class EeEmailChannel implements EeNotifyChannel {

    private final EeMailSender mailSender;

    public EeEmailChannel(EeMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String type() {
        return EeNotifyTypes.EMAIL;
    }

    @Override
    public void send(EeNotifyMessage msg) throws Exception {
        if (!mailSender.configured()) {
            DEException.throwException(Translator.get("ee_report_email_not_config"));
        }
        String html = StringUtils.defaultString(msg.getContent());
        StringBuilder appended = new StringBuilder();
        List<EeMailAttachment> attachments = new ArrayList<>();
        int inlineIndex = 0;
        for (EeNotifyAttachment attachment : msg.getAttachments()) {
            if (attachment.getData() == null) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(attachment.getName(), "attachment");
            if (attachment.isInline() && StringUtils.startsWith(attachment.getContentType(), "image/")) {
                String cid = StringUtils.defaultIfBlank(attachment.getContentId(), "img" + inlineIndex);
                inlineIndex++;
                if (!html.contains("cid:" + cid)) {
                    appended.append("<p><img style=\"max-width:100%\" src=\"cid:").append(cid).append("\"/></p>");
                }
                attachments.add(new EeMailAttachment(name, attachment.getData(), attachment.getContentType(), cid));
            } else {
                attachments.add(new EeMailAttachment(name, attachment.getData(), attachment.getContentType(), null));
            }
        }
        html = "<html><body>" + html + appended + "</body></html>";

        ClassLoader origin = Thread.currentThread().getContextClassLoader();
        // jakarta.mail 通过上下文类加载器查找 provider，在 Quartz 等线程中显式指定
        Thread.currentThread().setContextClassLoader(EeEmailChannel.class.getClassLoader());
        // 逐个收件人发送，避免收件人互相看到对方邮箱
        List<String> errors = new ArrayList<>();
        try {
            for (String target : msg.getTargets()) {
                try {
                    mailSender.send(List.of(target), msg.getTitle(), html, attachments);
                } catch (Exception e) {
                    errors.add(target + ": " + e.getMessage());
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(origin);
        }
        if (!errors.isEmpty()) {
            DEException.throwException(String.join("; ", errors));
        }
    }
}
