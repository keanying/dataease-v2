package io.dataease.ee.platform.lark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.lark.dto.LarkGroupItem;
import io.dataease.api.lark.vo.LarkGroupVO;
import io.dataease.api.lark.vo.LarkInfoVO;
import io.dataease.ee.platform.EePlatformDeps;
import io.dataease.ee.platform.EePlatformManageBase;
import io.dataease.ee.platform.message.EePlatformMessage;
import io.dataease.ee.platform.message.EePlatformMessageSender;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * 飞书 / 国际飞书（Lark Suite）：两者接口一致，仅开放平台域名不同。
 * 扫码 / 应用内登录、群列表、消息发送、tenant_access_token。
 */
public class EeLarkManage extends EePlatformManageBase<LarkInfoVO> implements EePlatformMessageSender {

    private final String name;

    private final int origin;

    private final String base;

    public EeLarkManage(EePlatformDeps deps, String name, int origin, String base) {
        super(deps);
        this.name = name;
        this.origin = origin;
        this.base = StringUtils.removeEnd(base, "/");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int origin() {
        return origin;
    }

    @Override
    protected Class<LarkInfoVO> type() {
        return LarkInfoVO.class;
    }

    @Override
    protected void check(LarkInfoVO config) {
        if (config == null || StringUtils.isAnyBlank(config.getAppId(), config.getAppSecret())) {
            throw new PlatformApiException(Translator.get("ee_sso_config_incomplete"));
        }
        evictToken("tenant", config.getAppId(), config.getAppSecret());
        tenantToken(config);
    }

    @Override
    protected String credentialKey(LarkInfoVO c) {
        return c.getAppId() + "|" + c.getAppSecret();
    }

    @Override
    protected void fillFlags(LarkInfoVO config, boolean enable, boolean valid) {
        config.setEnable(enable);
        config.setValid(valid);
    }

    public LarkInfoVO qrinfo() {
        LarkInfoVO config = requireActive();
        LarkInfoVO vo = new LarkInfoVO();
        vo.setAppId(config.getAppId());
        vo.setCallBack(config.getCallBack());
        vo.setEnable(true);
        vo.setValid(true);
        return vo;
    }

    @Override
    protected ExternalIdentity identityByCode(LarkInfoVO config, String code, String state) {
        ObjectNode body = EeJson.object();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        JsonNode token = call(http.postJson(base + "/open-apis/authen/v1/oidc/access_token", body,
                EeHttp.headers("Authorization", "Bearer " + appToken(config))), "oidc/access_token");
        String userToken = EeJson.text(token, "data.access_token");
        if (StringUtils.isBlank(userToken)) {
            throw new PlatformApiException("Lark: user access_token missing");
        }
        JsonNode info = call(http.get(base + "/open-apis/authen/v1/user_info", EeHttp.headers("Authorization", "Bearer " + userToken)), "user_info");
        String userId = EeJson.text(info, "data.user_id");
        String openId = EeJson.text(info, "data.open_id");
        String sub = StringUtils.defaultIfBlank(userId, openId);
        String email = StringUtils.defaultIfBlank(EeJson.text(info, "data.enterprise_email"), EeJson.text(info, "data.email"));
        return new ExternalIdentity(origin, sub, sub, EeJson.text(info, "data.name"), email, EeJson.text(info, "data.mobile"));
    }

    /**
     * 机器人所在的群
     */
    public LarkGroupVO getGroup() {
        LarkGroupVO vo = new LarkGroupVO();
        vo.setGroupList(new ArrayList<>());
        if (!available()) {
            vo.setValid(false);
            return vo;
        }
        try {
            LarkInfoVO config = config();
            String pageToken = null;
            int pages = 0;
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("page_size", "100");
                if (pageToken != null) {
                    query.put("page_token", pageToken);
                }
                JsonNode res = call(http.get(EeHttp.url(base + "/open-apis/im/v1/chats", query),
                        EeHttp.headers("Authorization", "Bearer " + tenantToken(config))), "im/v1/chats");
                JsonNode items = res.path("data").path("items");
                for (JsonNode item : items) {
                    LarkGroupItem group = new LarkGroupItem();
                    group.setChat_id(EeJson.text(item, "chat_id"));
                    group.setName(StringUtils.defaultIfBlank(EeJson.text(item, "name"), group.getChat_id()));
                    vo.getGroupList().add(group);
                }
                pageToken = res.path("data").path("has_more").asBoolean(false) ? EeJson.text(res, "data.page_token") : null;
            } while (pageToken != null && ++pages < 50);
            vo.setValid(true);
        } catch (Exception e) {
            LogUtil.warn(name + " getGroup failed: " + e.getMessage());
            vo.setValid(false);
        }
        return vo;
    }

    @Override
    public void sendToUsers(Collection<String> platformUserIds, EePlatformMessage message) {
        LarkInfoVO config = requireActive();
        ObjectNode content = content(config, message);
        for (String id : new LinkedHashSet<>(platformUserIds)) {
            send(config, StringUtils.startsWith(id, "ou_") ? "open_id" : "user_id", id, message, content);
        }
    }

    @Override
    public void sendToGroups(Collection<String> groupIds, EePlatformMessage message) {
        LarkInfoVO config = requireActive();
        ObjectNode content = content(config, message);
        for (String id : new LinkedHashSet<>(groupIds)) {
            send(config, "chat_id", id, message, content);
        }
    }

    /**
     * 飞书数据源使用的 tenant_access_token（未配置或不可用时返回 null）
     */
    public String tenantAccessToken() {
        LarkInfoVO config = config();
        if (config == null || StringUtils.isAnyBlank(config.getAppId(), config.getAppSecret())) {
            return null;
        }
        try {
            return tenantToken(config);
        } catch (Exception e) {
            LogUtil.warn(name + " tenant_access_token unavailable: " + e.getMessage());
            return null;
        }
    }

    private void send(LarkInfoVO config, String idType, String receiveId, EePlatformMessage message, ObjectNode content) {
        ObjectNode body = EeJson.object();
        body.put("receive_id", receiveId);
        body.put("msg_type", msgType(message));
        body.put("content", content.toString());
        call(http.postJson(EeHttp.url(base + "/open-apis/im/v1/messages", Map.of("receive_id_type", idType)), body,
                EeHttp.headers("Authorization", "Bearer " + tenantToken(config))), "im/v1/messages");
    }

    private String msgType(EePlatformMessage message) {
        return switch (message.getType()) {
            case TEXT -> "text";
            case MARKDOWN -> "interactive";
            case IMAGE -> "image";
            case FILE -> "file";
        };
    }

    private ObjectNode content(LarkInfoVO config, EePlatformMessage message) {
        ObjectNode content = EeJson.object();
        switch (message.getType()) {
            case TEXT -> content.put("text", message.getContent());
            case MARKDOWN -> {
                if (StringUtils.isNotBlank(message.getTitle())) {
                    ObjectNode header = content.putObject("header");
                    header.putObject("title").put("tag", "plain_text").put("content", message.getTitle());
                }
                content.putArray("elements").addObject().put("tag", "markdown").put("content", message.getContent());
            }
            case IMAGE -> {
                JsonNode res = call(http.postMultipart(base + "/open-apis/im/v1/images", Map.of("image_type", "message"), "image",
                        StringUtils.defaultIfBlank(message.getFileName(), "image.png"), message.getData(), null,
                        EeHttp.headers("Authorization", "Bearer " + tenantToken(config))), "im/v1/images");
                content.put("image_key", EeJson.text(res, "data.image_key"));
            }
            case FILE -> {
                String fileName = StringUtils.defaultIfBlank(message.getFileName(), "file");
                JsonNode res = call(http.postMultipart(base + "/open-apis/im/v1/files", Map.of("file_type", fileType(fileName), "file_name", fileName),
                        "file", fileName, message.getData(), null, EeHttp.headers("Authorization", "Bearer " + tenantToken(config))), "im/v1/files");
                content.put("file_key", EeJson.text(res, "data.file_key"));
            }
        }
        return content;
    }

    private String fileType(String fileName) {
        String ext = StringUtils.lowerCase(StringUtils.substringAfterLast(fileName, "."));
        return switch (StringUtils.defaultString(ext)) {
            case "pdf" -> "pdf";
            case "xls", "xlsx", "csv" -> "xls";
            case "doc", "docx" -> "doc";
            case "ppt", "pptx" -> "ppt";
            case "mp4" -> "mp4";
            default -> "stream";
        };
    }

    private String tenantToken(LarkInfoVO config) {
        return cachedToken("tenant", config.getAppId(), config.getAppSecret(), () -> {
            JsonNode res = call(http.postJson(base + "/open-apis/auth/v3/tenant_access_token/internal",
                    Map.of("app_id", config.getAppId(), "app_secret", config.getAppSecret()), null), "tenant_access_token");
            String token = EeJson.text(res, "tenant_access_token");
            if (StringUtils.isBlank(token)) {
                throw new PlatformApiException("Lark: tenant_access_token missing");
            }
            return token;
        });
    }

    private String appToken(LarkInfoVO config) {
        return cachedToken("app", config.getAppId(), config.getAppSecret(), () -> {
            JsonNode res = call(http.postJson(base + "/open-apis/auth/v3/app_access_token/internal",
                    Map.of("app_id", config.getAppId(), "app_secret", config.getAppSecret()), null), "app_access_token");
            String token = EeJson.text(res, "app_access_token");
            if (StringUtils.isBlank(token)) {
                throw new PlatformApiException("Lark: app_access_token missing");
            }
            return token;
        });
    }

    /**
     * 飞书接口：code 非 0 视为失败
     */
    private JsonNode call(EeHttp.Response response, String api) {
        JsonNode json;
        try {
            json = response.json();
        } catch (Exception e) {
            throw new PlatformApiException("Lark " + api + ": HTTP " + response.status());
        }
        String code = EeJson.text(json, "code");
        if (!response.ok() || (code != null && !"0".equals(code))) {
            throw new PlatformApiException("Lark " + api + ": " + StringUtils.defaultIfBlank(EeJson.text(json, "msg"), "HTTP " + response.status())
                    + (code == null ? "" : " (" + code + ")"));
        }
        return json;
    }
}
