package io.dataease.ee.message;

import io.dataease.api.communicate.api.CommunicateApi;
import io.dataease.api.communicate.dto.MessageDTO;
import io.dataease.constant.MessageEnum;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 消息发送中枢：按渠道分发（{@link EeMessageChannel}），并提供消息附件/图片的匿名下载（白名单路径）。
 *
 * <p>{@link #send(MessageDTO)} 主要供定时报告、阈值告警等后台任务以 Java 方式调用；
 * 通过 HTTP 调用 {@code /communicate/send} 仅允许系统管理员。</p>
 */
@RestController("communicateServer")
@RequestMapping("/communicate")
public class CommunicateServer implements CommunicateApi {

    private final ObjectProvider<EeMessageChannel> channels;

    private final EeCommunicateFileStore fileStore;

    public CommunicateServer(ObjectProvider<EeMessageChannel> channels, EeCommunicateFileStore fileStore) {
        this.channels = channels;
        this.fileStore = fileStore;
    }

    @Override
    public void send(MessageDTO dto) {
        HttpServletRequest request = ServletUtils.request();
        if (request != null && StringUtils.endsWith(request.getRequestURI(), "/communicate/send") && !V3UserUtil.isSysAdmin()) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (dto == null || dto.getMessageEnum() == null) {
            DEException.throwException(Translator.get("ee_message_channel_unsupported", "null"));
        }
        MessageEnum type = dto.getMessageEnum();
        if (type == MessageEnum.INNER) {
            // 站内消息没有独立收件箱，未读数由 EeMsgCountProvider 统计
            return;
        }
        EeMessageChannel channel = channels.orderedStream().filter(item -> item.supports(type)).findFirst().orElse(null);
        if (channel == null) {
            DEException.throwException(Translator.get("ee_message_channel_unsupported", type.name()));
        }
        channel.send(dto);
    }

    @Override
    public ResponseEntity<ByteArrayResource> down(String fileId, String fileName, String suffix) {
        byte[] body = fileStore.read(fileId);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }
        String name = StringUtils.defaultIfBlank(fileName, fileId);
        if (StringUtils.isNotBlank(suffix)) {
            name = name + "." + suffix;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(body.length).body(new ByteArrayResource(body));
    }

    @Override
    public ResponseEntity<byte[]> image(String imageId) {
        byte[] body = fileStore.read(imageId);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }
        boolean png = body.length > 4 && (body[0] & 0xFF) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
        return ResponseEntity.ok().contentType(png ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG).body(body);
    }
}
