package io.dataease.ee.platform.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.wecom.vo.WecomInfoVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.platform.EePlatformDeps;
import io.dataease.ee.platform.EePlatformManageBase;
import io.dataease.ee.platform.message.EePlatformMessage;
import io.dataease.ee.platform.message.EePlatformMessageSender;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 企业微信：扫码登录、应用消息（成员 / 应用群聊）
 */
@Component
public class EeWecomManage extends EePlatformManageBase<WecomInfoVO> implements EePlatformMessageSender {

    @Value("${dataease.ee.platform.wecom.api:https://qyapi.weixin.qq.com}")
    private String api;

    public EeWecomManage(EePlatformDeps deps) {
        super(deps);
    }

    @Override
    public String name() {
        return EeSsoConstants.WECOM;
    }

    @Override
    public int origin() {
        return EeConstants.ORIGIN_WECOM;
    }

    @Override
    protected Class<WecomInfoVO> type() {
        return WecomInfoVO.class;
    }

    @Override
    protected void check(WecomInfoVO config) {
        if (config == null || StringUtils.isAnyBlank(config.getCorpId(), config.getAgentId(), config.getAppSecret())) {
            throw new PlatformApiException(Translator.get("ee_sso_config_incomplete"));
        }
        evictToken("access", config.getCorpId(), config.getAppSecret());
        call(http.get(EeHttp.url(api + "/cgi-bin/agent/get", Map.of("access_token", accessToken(config), "agentid", config.getAgentId().trim())), null),
                "agent/get");
    }

    @Override
    protected String credentialKey(WecomInfoVO c) {
        return c.getCorpId() + "|" + c.getAgentId() + "|" + c.getAppSecret();
    }

    @Override
    protected void fillFlags(WecomInfoVO config, boolean enable, boolean valid) {
        config.setEnable(enable);
        config.setValid(valid);
    }

    public WecomInfoVO qrinfo() {
        WecomInfoVO config = requireActive();
        WecomInfoVO vo = new WecomInfoVO();
        vo.setCorpId(config.getCorpId());
        vo.setAgentId(config.getAgentId());
        vo.setCallBack(config.getCallBack());
        vo.setEnable(true);
        vo.setValid(true);
        return vo;
    }

    @Override
    protected ExternalIdentity identityByCode(WecomInfoVO config, String code, String state) {
        JsonNode res = call(http.get(EeHttp.url(api + "/cgi-bin/auth/getuserinfo", Map.of("access_token", accessToken(config), "code", code)), null),
                "auth/getuserinfo");
        String userId = StringUtils.defaultIfBlank(EeJson.text(res, "userid"), EeJson.text(res, "UserId"));
        if (StringUtils.isBlank(userId)) {
            throw new PlatformApiException(Translator.get("ee_wecom_not_member"));
        }
        String name = null;
        String email = null;
        String mobile = null;
        try {
            JsonNode user = call(http.get(EeHttp.url(api + "/cgi-bin/user/get", Map.of("access_token", accessToken(config), "userid", userId)), null),
                    "user/get");
            name = EeJson.text(user, "name");
            email = StringUtils.defaultIfBlank(EeJson.text(user, "biz_mail"), EeJson.text(user, "email"));
            mobile = EeJson.text(user, "mobile");
        } catch (Exception e) {
            LogUtil.warn("wecom user detail unavailable: " + e.getMessage());
        }
        return new ExternalIdentity(origin(), userId, userId, name, email, mobile);
    }

    @Override
    public void sendToUsers(Collection<String> platformUserIds, EePlatformMessage message) {
        WecomInfoVO config = requireActive();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(platformUserIds));
        for (int i = 0; i < ids.size(); i += 1000) {
            ObjectNode body = message(config, message);
            body.put("touser", String.join("|", ids.subList(i, Math.min(ids.size(), i + 1000))));
            body.put("agentid", Long.parseLong(config.getAgentId().trim()));
            call(http.postJson(EeHttp.url(api + "/cgi-bin/message/send", Map.of("access_token", accessToken(config))), body, null), "message/send");
        }
    }

    @Override
    public void sendToGroups(Collection<String> groupIds, EePlatformMessage message) {
        WecomInfoVO config = requireActive();
        for (String chatId : new LinkedHashSet<>(groupIds)) {
            ObjectNode body = message(config, message);
            body.put("chatid", chatId);
            call(http.postJson(EeHttp.url(api + "/cgi-bin/appchat/send", Map.of("access_token", accessToken(config))), body, null), "appchat/send");
        }
    }

    private ObjectNode message(WecomInfoVO config, EePlatformMessage message) {
        ObjectNode body = EeJson.object();
        switch (message.getType()) {
            case TEXT -> {
                body.put("msgtype", "text");
                body.putObject("text").put("content", message.getContent());
            }
            case MARKDOWN -> {
                body.put("msgtype", "markdown");
                String content = StringUtils.isBlank(message.getTitle()) ? message.getContent() : "**" + message.getTitle() + "**\n" + message.getContent();
                body.putObject("markdown").put("content", content);
            }
            case IMAGE -> {
                body.put("msgtype", "image");
                body.putObject("image").put("media_id", upload(config, "image", message));
            }
            case FILE -> {
                body.put("msgtype", "file");
                body.putObject("file").put("media_id", upload(config, "file", message));
            }
        }
        return body;
    }

    private String upload(WecomInfoVO config, String type, EePlatformMessage message) {
        JsonNode res = call(http.postMultipart(EeHttp.url(api + "/cgi-bin/media/upload", Map.of("access_token", accessToken(config), "type", type)),
                null, "media", StringUtils.defaultIfBlank(message.getFileName(), type), message.getData(), null, null), "media/upload");
        String mediaId = EeJson.text(res, "media_id");
        if (StringUtils.isBlank(mediaId)) {
            throw new PlatformApiException("WeCom media/upload: media_id missing");
        }
        return mediaId;
    }

    private String accessToken(WecomInfoVO config) {
        return cachedToken("access", config.getCorpId(), config.getAppSecret(), () -> {
            JsonNode res = call(http.get(EeHttp.url(api + "/cgi-bin/gettoken", Map.of("corpid", config.getCorpId(), "corpsecret", config.getAppSecret())), null),
                    "gettoken");
            String token = EeJson.text(res, "access_token");
            if (StringUtils.isBlank(token)) {
                throw new PlatformApiException("WeCom gettoken: access_token missing");
            }
            return token;
        });
    }

    private JsonNode call(EeHttp.Response response, String api) {
        JsonNode json;
        try {
            json = response.json();
        } catch (Exception e) {
            throw new PlatformApiException("WeCom " + api + ": HTTP " + response.status());
        }
        String errcode = EeJson.text(json, "errcode");
        if (!response.ok() || (errcode != null && !"0".equals(errcode))) {
            throw new PlatformApiException("WeCom " + api + ": " + StringUtils.defaultIfBlank(EeJson.text(json, "errmsg"), "HTTP " + response.status())
                    + (errcode == null ? "" : " (" + errcode + ")"));
        }
        return json;
    }
}
