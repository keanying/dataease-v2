package io.dataease.ee.report.notify;

import io.dataease.ee.webhook.EeWebhookSender;
import io.dataease.ee.webhook.dao.XpackWebhook;
import io.dataease.ee.webhook.dao.XpackWebhookRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Webhook 渠道：targets 为 xpack_webhook 的 ID，只发送属于消息所在组织的 Webhook。
 *
 * <p>模板占位符：title、content（纯文本）、html、messageId、source、sourceId。</p>
 */
@Component
public class EeWebhookNotifyChannel implements EeNotifyChannel {

    private final EeWebhookSender webhookSender;

    private final XpackWebhookRepository webhookRepository;

    public EeWebhookNotifyChannel(EeWebhookSender webhookSender, XpackWebhookRepository webhookRepository) {
        this.webhookSender = webhookSender;
        this.webhookRepository = webhookRepository;
    }

    @Override
    public String type() {
        return EeNotifyTypes.WEBHOOK;
    }

    @Override
    public void send(EeNotifyMessage msg) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", StringUtils.defaultString(msg.getTitle()));
        payload.put("content", EeNotifyText.plain(msg.getContent()));
        payload.put("html", StringUtils.defaultString(msg.getContent()));
        payload.put("messageId", msg.getSource() + "-" + msg.getSourceId() + "-" + System.currentTimeMillis());
        payload.put("source", StringUtils.defaultString(msg.getSource()));
        payload.put("sourceId", msg.getSourceId() == null ? "" : msg.getSourceId());
        List<String> errors = new ArrayList<>();
        for (String target : msg.getTargets()) {
            XpackWebhook webhook = StringUtils.isNumeric(target) ? webhookRepository.findById(Long.valueOf(target)).orElse(null) : null;
            if (webhook == null || (msg.getOid() != null && !Objects.equals(msg.getOid(), webhook.getOid()))) {
                errors.add(target + ": " + Translator.get("ee_webhook_not_exist"));
                continue;
            }
            EeWebhookSender.Result result = webhookSender.send(webhook, payload);
            if (!result.success()) {
                errors.add(webhook.getName() + ": " + StringUtils.defaultIfBlank(result.error(), "HTTP " + result.status()));
            }
        }
        if (!errors.isEmpty()) {
            DEException.throwException(String.join("; ", errors));
        }
    }
}
