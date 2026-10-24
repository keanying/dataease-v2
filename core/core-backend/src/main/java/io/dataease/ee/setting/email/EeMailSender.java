package io.dataease.ee.setting.email;

import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.utils.LogUtil;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 邮件发送（使用“系统参数-邮件设置”中的 SMTP 配置），供定时报告、阈值告警等模块复用。
 *
 * <pre>
 * eeMailSender.send("a@x.com,b@y.com", "主题", "&lt;p&gt;正文&lt;/p&gt;", List.of(EeMailAttachment.of("报表.pdf", bytes)));
 * </pre>
 */
@Component
public class EeMailSender {

    public static final String HOST = "email.host";
    public static final String PORT = "email.port";
    public static final String ACCOUNT = "email.account";
    public static final String PWD = "email.pwd";
    public static final String FROM = "email.from";
    public static final String RECIPIENT = "email.reci";
    public static final String SSL = "email.ssl";
    public static final String TLS = "email.tsl";

    private static final int CONNECTION_TIMEOUT = 10_000;

    private static final int TIMEOUT = 30_000;

    private final EeSettingManage settingManage;

    public EeMailSender(EeSettingManage settingManage) {
        this.settingManage = settingManage;
    }

    /**
     * 当前保存的邮件设置（键 -> 值）
     */
    public Map<String, String> currentConfig() {
        Map<String, String> config = new HashMap<>();
        for (CoreSysSetting setting : settingManage.group(EeSettingConstants.EMAIL_PREFIX)) {
            config.put(setting.getPkey(), setting.getPval());
        }
        return config;
    }

    /**
     * 是否已配置 SMTP 服务
     */
    public boolean configured() {
        Map<String, String> config = currentConfig();
        return StringUtils.isNoneBlank(config.get(HOST), config.get(PORT));
    }

    /**
     * 发送 HTML 邮件，收件人可用逗号/分号分隔
     */
    public void send(String to, String subject, String html, List<EeMailAttachment> attachments) {
        send(splitAddress(to), subject, html, attachments);
    }

    public void send(Collection<String> to, String subject, String html, List<EeMailAttachment> attachments) {
        send(currentConfig(), to, subject, html, attachments);
    }

    /**
     * 使用指定配置发送（用于校验未保存的配置）
     */
    public void send(Map<String, String> config, Collection<String> to, String subject, String html, List<EeMailAttachment> attachments) {
        if (CollectionUtils.isEmpty(to)) {
            DEException.throwException(Translator.get("ee_settings.email_recipient_required"));
        }
        JavaMailSenderImpl sender = build(config);
        try {
            MimeMessage message = sender.createMimeMessage();
            boolean multipart = CollectionUtils.isNotEmpty(attachments);
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(from(config));
            helper.setTo(to.stream().map(String::trim).filter(StringUtils::isNotBlank).distinct().toArray(String[]::new));
            helper.setSubject(StringUtils.defaultString(subject));
            helper.setText(StringUtils.defaultString(html), true);
            if (multipart) {
                for (EeMailAttachment attachment : attachments) {
                    if (attachment == null || attachment.content() == null) {
                        continue;
                    }
                    ByteArrayResource resource = new ByteArrayResource(attachment.content());
                    if (StringUtils.isNotBlank(attachment.contentId())) {
                        helper.addInline(attachment.contentId(), resource,
                                StringUtils.defaultIfBlank(attachment.contentType(), "application/octet-stream"));
                    } else if (StringUtils.isNotBlank(attachment.contentType())) {
                        helper.addAttachment(attachment.fileName(), resource, attachment.contentType());
                    } else {
                        helper.addAttachment(attachment.fileName(), resource);
                    }
                }
            }
            sender.send(message);
        } catch (MessagingException | org.springframework.mail.MailException e) {
            LogUtil.error("send mail failed: " + e.getMessage(), e);
            DEException.throwException(Translator.get("ee_settings.email_send_failed", rootMessage(e)));
        }
    }

    /**
     * 仅测试与 SMTP 服务的连接与认证
     */
    public void testConnection(Map<String, String> config) {
        JavaMailSenderImpl sender = build(config);
        try {
            sender.testConnection();
        } catch (MessagingException e) {
            DEException.throwException(Translator.get("ee_settings.email_send_failed", rootMessage(e)));
        }
    }

    private JavaMailSenderImpl build(Map<String, String> config) {
        String host = StringUtils.trimToNull(config.get(HOST));
        int port = NumberUtils.toInt(StringUtils.trimToEmpty(config.get(PORT)), -1);
        if (host == null || port <= 0 || port > 65535) {
            DEException.throwException(Translator.get("ee_settings.email_not_configured"));
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        String account = StringUtils.trimToNull(config.get(ACCOUNT));
        boolean ssl = StringUtils.equalsIgnoreCase(config.get(SSL), "true");
        boolean tls = StringUtils.equalsIgnoreCase(config.get(TLS), "true");
        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(CONNECTION_TIMEOUT));
        props.setProperty("mail.smtp.timeout", String.valueOf(TIMEOUT));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(TIMEOUT));
        if (account != null) {
            sender.setUsername(account);
            sender.setPassword(StringUtils.defaultString(config.get(PWD)));
            props.setProperty("mail.smtp.auth", "true");
        }
        if (ssl) {
            props.setProperty("mail.smtp.ssl.enable", "true");
            props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        }
        if (tls) {
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }

    private InternetAddress from(Map<String, String> config) {
        String from = StringUtils.trimToNull(config.get(FROM));
        if (from == null) {
            from = StringUtils.trimToNull(config.get(ACCOUNT));
        }
        if (from == null) {
            DEException.throwException(Translator.get("ee_settings.email_from_required"));
        }
        try {
            return new InternetAddress(from, true);
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_settings.email_from_required"));
            return null;
        }
    }

    public static List<String> splitAddress(String to) {
        if (StringUtils.isBlank(to)) {
            return Collections.emptyList();
        }
        return Arrays.stream(to.split("[,;，；\\s]+")).map(String::trim).filter(StringUtils::isNotBlank).distinct().toList();
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }
}
