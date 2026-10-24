package io.dataease.ee.report.notify;

import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * 邮件渠道：读取 core_sys_setting 中的 email.* 配置，通过 SMTP 发送 HTML 邮件。
 *
 * <p>正文为 HTML；内嵌图片以 {@code cid:<contentId>} 引用（正文未引用的内嵌图片会追加到正文末尾），
 * 其余附件作为普通附件。</p>
 */
@Component
public class EeEmailChannel implements EeNotifyChannel {

    public static final String KEY_HOST = "email.host";
    public static final String KEY_PORT = "email.port";
    public static final String KEY_ACCOUNT = "email.account";
    public static final String KEY_PWD = "email.pwd";
    public static final String KEY_FROM = "email.from";
    public static final String KEY_SSL = "email.ssl";
    public static final String KEY_TLS = "email.tsl";

    private static final int TIMEOUT_MS = 30000;

    private final EeSettingManage settingManage;

    public EeEmailChannel(EeSettingManage settingManage) {
        this.settingManage = settingManage;
    }

    @Override
    public String type() {
        return EeNotifyTypes.EMAIL;
    }

    @Override
    public void send(EeNotifyMessage msg) throws Exception {
        String host = settingManage.get(KEY_HOST, null);
        if (StringUtils.isBlank(host)) {
            DEException.throwException(Translator.get("ee_report_email_not_config"));
        }
        String port = settingManage.get(KEY_PORT, "25");
        String account = settingManage.get(KEY_ACCOUNT, null);
        String pwd = settingManage.get(KEY_PWD, null);
        String from = settingManage.get(KEY_FROM, account);
        boolean ssl = settingManage.getBool(KEY_SSL, false);
        boolean tls = settingManage.getBool(KEY_TLS, false);
        if (StringUtils.isBlank(from)) {
            DEException.throwException(Translator.get("ee_report_email_no_from"));
        }

        Properties props = new Properties();
        String protocol = ssl ? "smtps" : "smtp";
        props.put("mail.transport.protocol", protocol);
        props.put("mail." + protocol + ".host", host.trim());
        props.put("mail." + protocol + ".port", port.trim());
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail." + protocol + ".timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail." + protocol + ".writetimeout", String.valueOf(TIMEOUT_MS));
        boolean auth = StringUtils.isNotBlank(account);
        props.put("mail." + protocol + ".auth", String.valueOf(auth));
        if (ssl) {
            props.put("mail.smtps.ssl.enable", "true");
            props.put("mail.smtps.ssl.checkserveridentity", "true");
        }
        if (tls) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        Session session = Session.getInstance(props);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from.trim()));
        List<InternetAddress> to = new ArrayList<>();
        for (String target : msg.getTargets()) {
            to.add(new InternetAddress(target.trim()));
        }
        message.setRecipients(Message.RecipientType.TO, to.toArray(new InternetAddress[0]));
        message.setSubject(StringUtils.defaultString(msg.getTitle()), StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        message.setContent(buildBody(msg));
        message.saveChanges();

        ClassLoader origin = Thread.currentThread().getContextClassLoader();
        // jakarta.mail 通过上下文类加载器查找 provider，在 Quartz 等线程中显式指定
        Thread.currentThread().setContextClassLoader(EeEmailChannel.class.getClassLoader());
        try (Transport transport = session.getTransport(protocol)) {
            if (auth) {
                transport.connect(host.trim(), Integer.parseInt(port.trim()), account.trim(), StringUtils.defaultString(pwd));
            } else {
                transport.connect();
            }
            transport.sendMessage(message, message.getAllRecipients());
        } finally {
            Thread.currentThread().setContextClassLoader(origin);
        }
    }

    private Multipart buildBody(EeNotifyMessage msg) throws MessagingException {
        String html = StringUtils.defaultString(msg.getContent());
        StringBuilder appended = new StringBuilder();
        List<EeNotifyAttachment> inlines = new ArrayList<>();
        List<EeNotifyAttachment> files = new ArrayList<>();
        for (EeNotifyAttachment attachment : msg.getAttachments()) {
            if (attachment.isInline() && StringUtils.startsWith(attachment.getContentType(), "image/")) {
                if (StringUtils.isBlank(attachment.getContentId())) {
                    attachment.setContentId("img" + inlines.size());
                }
                inlines.add(attachment);
                if (!html.contains("cid:" + attachment.getContentId())) {
                    appended.append("<p><img style=\"max-width:100%\" src=\"cid:").append(attachment.getContentId()).append("\"/></p>");
                }
            } else {
                files.add(attachment);
            }
        }
        html = "<html><body>" + html + appended + "</body></html>";

        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setText(html, StandardCharsets.UTF_8.name(), "html");
        related.addBodyPart(htmlPart);
        for (EeNotifyAttachment inline : inlines) {
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new DataHandler(new ByteArrayDataSource(inline.getData(), inline.getContentType())));
            part.setContentID("<" + inline.getContentId() + ">");
            part.setDisposition(Part.INLINE);
            part.setFileName(encode(inline.getName()));
            related.addBodyPart(part);
        }
        if (files.isEmpty()) {
            return related;
        }
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart relatedPart = new MimeBodyPart();
        relatedPart.setContent(related);
        mixed.addBodyPart(relatedPart);
        for (EeNotifyAttachment file : files) {
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new DataHandler(new ByteArrayDataSource(file.getData(), file.getContentType())));
            part.setDisposition(Part.ATTACHMENT);
            part.setFileName(encode(file.getName()));
            mixed.addBodyPart(part);
        }
        return mixed;
    }

    private String encode(String name) {
        try {
            return MimeUtility.encodeText(StringUtils.defaultIfBlank(name, "attachment"), StandardCharsets.UTF_8.name(), "B");
        } catch (Exception e) {
            return name;
        }
    }
}
