package io.dataease.ee.webhook;

import io.dataease.api.webhook.WebhookApi;
import io.dataease.api.webhook.request.WebhookSwitchRequest;
import io.dataease.api.webhook.vo.WebhookGridVO;
import io.dataease.api.webhook.vo.WebhookOption;
import io.dataease.api.webhook.vo.WebhookVO;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.KeywordRequest;
import io.dataease.result.PageResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 管理（组织管理中心 → Webhook，菜单 63），按当前组织隔离
 */
@RestController("webhookServer")
@RequestMapping("/webhook")
public class WebhookServer implements WebhookApi {

    public static final long WEBHOOK_MENU_ID = 63L;

    /**
     * 阈值告警菜单（选择接收 webhook）
     */
    public static final long THRESHOLD_MENU_ID = 62L;

    /**
     * 定时报告菜单
     */
    public static final long REPORT_MENU_ID = 13L;

    private final EeWebhookManage webhookManage;

    private final EeWebhookSender sender;

    private final EeMenuManage menuManage;

    public WebhookServer(EeWebhookManage webhookManage, EeWebhookSender sender, EeMenuManage menuManage) {
        this.webhookManage = webhookManage;
        this.sender = sender;
        this.menuManage = menuManage;
    }

    private void check() {
        if (!menuManage.canAccess(WEBHOOK_MENU_ID)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    @Override
    public PageResult<WebhookGridVO> pager(int goPage, int pageSize, KeywordRequest request) {
        check();
        return webhookManage.pager(goPage, pageSize, request == null ? null : request.getKeyword());
    }

    @Override
    public void save(WebhookVO creator) {
        check();
        webhookManage.save(creator);
    }

    @Override
    public void switchSsl(WebhookSwitchRequest request) {
        check();
        webhookManage.switchSsl(request.getId(), request.isSsl());
    }

    @Override
    public void delete(List<Long> ids) {
        check();
        webhookManage.delete(ids);
    }

    @Override
    public WebhookVO get(Long id) {
        check();
        return webhookManage.get(id);
    }

    @Override
    public List<WebhookOption> options() {
        if (!menuManage.canAccess(WEBHOOK_MENU_ID) && !menuManage.canAccess(THRESHOLD_MENU_ID)
                && !menuManage.canAccess(REPORT_MENU_ID)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return webhookManage.options();
    }

    /**
     * 发送一条测试消息（前端未使用，便于管理员验证配置）
     */
    @PostMapping("/test/{id}")
    public Map<String, Object> test(@PathVariable("id") Long id) {
        check();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", Translator.get("ee_webhook_test_title"));
        payload.put("content", Translator.get("ee_webhook_test_content"));
        payload.put("messageId", String.valueOf(System.currentTimeMillis()));
        EeWebhookSender.Result result = sender.send(webhookManage.getInOrg(id), payload);
        if (!result.success()) {
            DEException.throwException(Translator.get("ee_webhook_send_failed", result.error()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", result.status());
        data.put("body", result.body());
        return data;
    }
}
