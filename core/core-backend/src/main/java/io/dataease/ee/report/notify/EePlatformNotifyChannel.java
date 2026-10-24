package io.dataease.ee.report.notify;

import io.dataease.ee.platform.message.EePlatformMessage;
import io.dataease.ee.platform.message.EePlatformMessageSender;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

/**
 * 企业微信、钉钉、飞书、国际飞书的个人与群渠道：转换为 {@link EePlatformMessage} 后交给平台模块发送。
 *
 * <p>先发送标题 + 正文（Markdown），再逐个发送内嵌图片（图片消息）与附件（文件消息）。
 * 个人渠道的 targets 已是平台用户 ID，群渠道的 targets 为群 ID。Bean 在 {@link EePlatformNotifyChannelConfig} 中注册。</p>
 */
public class EePlatformNotifyChannel implements EeNotifyChannel {

    private final String type;

    private final int origin;

    private final boolean group;

    private final ObjectProvider<EePlatformMessageSender> senders;

    public EePlatformNotifyChannel(String type, int origin, boolean group, ObjectProvider<EePlatformMessageSender> senders) {
        this.type = type;
        this.origin = origin;
        this.group = group;
        this.senders = senders;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public void send(EeNotifyMessage msg) throws Exception {
        EePlatformMessageSender sender = senders.orderedStream()
                .filter(s -> s.origin() == origin)
                .findFirst()
                .orElse(null);
        if (sender == null || !sender.available()) {
            DEException.throwException(Translator.get("ee_report_notify_channel_unavailable", type));
        }
        Consumer<EePlatformMessage> deliver = message -> {
            if (group) {
                sender.sendToGroups(msg.getTargets(), message);
            } else {
                sender.sendToUsers(msg.getTargets(), message);
            }
        };
        String text = EeNotifyText.plain(msg.getContent());
        String title = StringUtils.defaultString(msg.getTitle());
        deliver.accept(EePlatformMessage.markdown(title, StringUtils.isBlank(text) ? title : text));
        for (EeNotifyAttachment attachment : msg.getAttachments()) {
            if (attachment.getData() == null) {
                continue;
            }
            String name = StringUtils.defaultIfBlank(attachment.getName(), "attachment");
            if (StringUtils.startsWith(attachment.getContentType(), "image/")) {
                deliver.accept(EePlatformMessage.image(name, attachment.getData()));
            } else {
                deliver.accept(EePlatformMessage.file(name, attachment.getData()));
            }
        }
    }
}
