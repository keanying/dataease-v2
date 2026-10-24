package io.dataease.ee.webhook;

import io.dataease.api.communicate.dto.MessageDTO;
import io.dataease.api.communicate.dto.MessageFile;
import io.dataease.constant.MessageEnum;
import io.dataease.ee.message.EeCommunicateFileStore;
import io.dataease.ee.message.EeMessageChannel;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WEBHOOK 消息渠道：{@code recipientList} 为 webhook ID。
 *
 * <p>占位符：title、content（content 字节按 UTF-8 解码）、messageId；有附件时附件保存到消息文件存储，
 * {@code ${files}} 为以逗号分隔的相对下载路径 {@code communicate/down/{id}/{name}/{suffix}}。</p>
 */
@Component
public class EeWebhookChannel implements EeMessageChannel {

    private final EeWebhookSender sender;

    private final EeCommunicateFileStore fileStore;

    public EeWebhookChannel(EeWebhookSender sender, EeCommunicateFileStore fileStore) {
        this.sender = sender;
        this.fileStore = fileStore;
    }

    @Override
    public boolean supports(MessageEnum messageEnum) {
        return messageEnum == MessageEnum.WEBHOOK;
    }

    @Override
    public void send(MessageDTO message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", StringUtils.defaultString(message.getTitle()));
        payload.put("content", message.getContent() == null ? "" : new String(message.getContent(), StandardCharsets.UTF_8));
        payload.put("messageId", message.getMessageId() == null ? "" : String.valueOf(message.getMessageId()));
        payload.put("files", String.join(",", storeFiles(message)));
        List<String> errors = new ArrayList<>();
        for (String recipient : CollectionUtils.emptyIfNull(message.getRecipientList())) {
            if (!StringUtils.isNumeric(recipient)) {
                continue;
            }
            EeWebhookSender.Result result = sender.send(Long.parseLong(recipient), payload);
            if (!result.success()) {
                errors.add(recipient + ": " + result.error());
            }
        }
        if (!errors.isEmpty()) {
            DEException.throwException(Translator.get("ee_webhook_send_failed", String.join("; ", errors)));
        }
    }

    private List<String> storeFiles(MessageDTO message) {
        List<String> links = new ArrayList<>();
        try {
            for (MessageFile file : CollectionUtils.emptyIfNull(message.getMessageFileList())) {
                links.add(link(fileStore.save(file.getBody()), file.getFileName()));
            }
            for (File file : CollectionUtils.emptyIfNull(message.getFileList())) {
                if (file != null && file.isFile()) {
                    links.add(link(fileStore.save(Files.readAllBytes(file.toPath())), file.getName()));
                }
            }
        } catch (Exception e) {
            DEException.throwException(e);
        }
        return links;
    }

    private static String link(String id, String fileName) {
        String name = StringUtils.defaultIfBlank(fileName, id);
        String base = name;
        String suffix = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            suffix = name.substring(dot + 1);
        }
        return "communicate/down/" + id + "/" + java.net.URLEncoder.encode(base, StandardCharsets.UTF_8).replace("+", "%20")
                + "/" + (suffix.isEmpty() ? "bin" : suffix);
    }
}
