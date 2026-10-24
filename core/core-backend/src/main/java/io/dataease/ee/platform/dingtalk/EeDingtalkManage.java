package io.dataease.ee.platform.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.dingtalk.dto.DingtalkSignatureInfo;
import io.dataease.api.dingtalk.vo.DingtalkChatItem;
import io.dataease.api.dingtalk.vo.DingtalkInfoVO;
import io.dataease.api.lark.dto.LarkGroupItem;
import io.dataease.api.lark.vo.LarkGroupVO;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.platform.EePlatformDeps;
import io.dataease.ee.platform.EePlatformManageBase;
import io.dataease.ee.platform.message.EePlatformMessage;
import io.dataease.ee.platform.message.EePlatformMessageSender;
import io.dataease.ee.sso.EeSsoConstants;
import io.dataease.ee.sso.support.EeHttp;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.ee.sso.support.EeSsoKeys;
import io.dataease.ee.sso.support.ExternalIdentity;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 钉钉：扫码登录（sns_authorize）、应用内免登、工作通知与群机器人消息、JSAPI 签名
 */
@Component
public class EeDingtalkManage extends EePlatformManageBase<DingtalkInfoVO> implements EePlatformMessageSender {

    @Value("${dataease.ee.platform.dingtalk.oapi:https://oapi.dingtalk.com}")
    private String oapi;

    @Value("${dataease.ee.platform.dingtalk.api:https://api.dingtalk.com}")
    private String api;

    public EeDingtalkManage(EePlatformDeps deps) {
        super(deps);
    }

    @Override
    public String name() {
        return EeSsoConstants.DINGTALK;
    }

    @Override
    public int origin() {
        return EeConstants.ORIGIN_DINGTALK;
    }

    @Override
    protected Class<DingtalkInfoVO> type() {
        return DingtalkInfoVO.class;
    }

    @Override
    protected void check(DingtalkInfoVO config) {
        if (config == null || StringUtils.isAnyBlank(config.getAppKey(), config.getAppSecret(), config.getAgentId())) {
            throw new PlatformApiException(Translator.get("ee_sso_config_incomplete"));
        }
        evictToken("access", config.getAppKey(), config.getAppSecret());
        accessToken(config);
    }

    @Override
    protected String credentialKey(DingtalkInfoVO c) {
        return c.getCorpId() + "|" + c.getAgentId() + "|" + c.getAppKey() + "|" + c.getAppSecret();
    }

    @Override
    protected void fillFlags(DingtalkInfoVO config, boolean enable, boolean valid) {
        config.setEnable(enable);
        config.setValid(valid);
    }

    public DingtalkInfoVO qrinfo() {
        DingtalkInfoVO config = requireActive();
        DingtalkInfoVO vo = new DingtalkInfoVO();
        vo.setAppKey(config.getAppKey());
        vo.setCorpId(config.getCorpId());
        vo.setAgentId(config.getAgentId());
        vo.setCallBack(config.getCallBack());
        vo.setEnable(true);
        vo.setValid(true);
        vo.setChatList(null);
        return vo;
    }

    @Override
    protected ExternalIdentity identityByCode(DingtalkInfoVO config, String code, String state) {
        String userId;
        if (StringUtils.contains(state, "-client")) {
            // 应用内免登码
            JsonNode res = call(http.postJson(EeHttp.url(oapi + "/topapi/v2/user/getuserinfo", Map.of("access_token", accessToken(config))),
                    Map.of("code", code), null), "getuserinfo");
            userId = EeJson.text(res, "result.userid");
        } else {
            // 扫码登录临时授权码
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = Base64.getEncoder().encodeToString(new HmacUtils(HmacAlgorithms.HMAC_SHA_256, config.getAppSecret()).hmac(timestamp));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("accessKey", config.getAppKey());
            query.put("timestamp", timestamp);
            query.put("signature", signature);
            JsonNode res = call(http.postJson(EeHttp.url(oapi + "/sns/getuserinfo_bycode", query), Map.of("tmp_auth_code", code), null),
                    "getuserinfo_bycode");
            String unionId = EeJson.text(res, "user_info.unionid");
            if (StringUtils.isBlank(unionId)) {
                throw new PlatformApiException("DingTalk: unionid missing");
            }
            JsonNode user = call(http.postJson(EeHttp.url(oapi + "/topapi/user/getbyunionid", Map.of("access_token", accessToken(config))),
                    Map.of("unionid", unionId), null), "getbyunionid");
            userId = EeJson.text(user, "result.userid");
            if (StringUtils.isBlank(userId)) {
                throw new PlatformApiException(Translator.get("ee_dingtalk_not_member"));
            }
        }
        if (StringUtils.isBlank(userId)) {
            throw new PlatformApiException("DingTalk: userid missing");
        }
        String name = null;
        String email = null;
        String mobile = null;
        try {
            JsonNode detail = call(http.postJson(EeHttp.url(oapi + "/topapi/v2/user/get", Map.of("access_token", accessToken(config))),
                    Map.of("userid", userId, "language", "zh_CN"), null), "user/get");
            name = EeJson.text(detail, "result.name");
            email = StringUtils.defaultIfBlank(EeJson.text(detail, "result.email"), EeJson.text(detail, "result.org_email"));
            mobile = EeJson.text(detail, "result.mobile");
        } catch (Exception e) {
            LogUtil.warn("dingtalk user detail unavailable: " + e.getMessage());
        }
        return new ExternalIdentity(origin(), userId, userId, name, email, mobile);
    }

    public LarkGroupVO getGroup() {
        LarkGroupVO vo = new LarkGroupVO();
        List<LarkGroupItem> list = new ArrayList<>();
        DingtalkInfoVO config = config();
        vo.setValid(available());
        if (config != null && config.getChatList() != null) {
            for (DingtalkChatItem chat : config.getChatList()) {
                if (chat == null || StringUtils.isBlank(chat.getId())) {
                    continue;
                }
                LarkGroupItem item = new LarkGroupItem();
                item.setChat_id(chat.getId());
                item.setName(StringUtils.defaultIfBlank(chat.getName(), chat.getId()));
                list.add(item);
            }
        }
        vo.setGroupList(list);
        return vo;
    }

    /**
     * JSAPI 鉴权签名（选择群聊 chooseChat 使用）
     */
    public DingtalkSignatureInfo signature(String currentUrl) {
        DingtalkInfoVO config = config();
        if (config == null || StringUtils.isAnyBlank(config.getAppKey(), config.getAppSecret())) {
            DEException.throwException(Translator.get("ee_sso_config_not_found"));
        }
        if (StringUtils.isBlank(currentUrl)) {
            DEException.throwException(Translator.get("ee_sso_config_incomplete"));
        }
        String ticket;
        try {
            ticket = cachedToken("jsapi", config.getAppKey(), config.getAppSecret(), () -> EeJson.text(
                    call(http.get(EeHttp.url(oapi + "/get_jsapi_ticket", Map.of("access_token", accessToken(config))), null), "get_jsapi_ticket"),
                    "ticket"));
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_sso_remote_failed", e.getMessage()));
            return null;
        }
        String nonce = EeSsoKeys.randomHex(8);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String plain = "jsapi_ticket=" + ticket + "&noncestr=" + nonce + "&timestamp=" + timestamp + "&url=" + decodeUrl(currentUrl);
        DingtalkSignatureInfo info = new DingtalkSignatureInfo();
        info.setCorpId(config.getCorpId());
        info.setAgentId(config.getAgentId());
        info.setTimeStamp(timestamp);
        info.setNonceStr(nonce);
        info.setSignature(DigestUtils.sha1Hex(plain));
        return info;
    }

    /**
     * 检查机器人是否在群内
     */
    public void checkChat(String chatId) {
        DingtalkInfoVO config = config();
        if (config == null || StringUtils.isAnyBlank(config.getAppKey(), config.getAppSecret())) {
            DEException.throwException(Translator.get("ee_sso_config_not_found"));
        }
        if (StringUtils.isBlank(chatId)) {
            DEException.throwException(Translator.get("ee_sso_config_incomplete"));
        }
        JsonNode res;
        try {
            EeHttp.Response response = http.postJson(api + "/v1.0/robot/getBotListInGroup", Map.of("openConversationId", chatId),
                    EeHttp.headers("x-acs-dingtalk-access-token", accessToken(config)));
            res = response.json();
            if (!response.ok()) {
                throw new PlatformApiException(StringUtils.defaultIfBlank(EeJson.text(res, "message"), "HTTP " + response.status()));
            }
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_dingtalk_chat_check_failed", e.getMessage()));
            return;
        }
        String robotCode = robotCode(config);
        JsonNode bots = res.get("chatbotInstanceVOList");
        if (bots != null && bots.isArray()) {
            for (JsonNode bot : bots) {
                if (robotCode.equals(EeJson.text(bot, "robotCode"))) {
                    return;
                }
            }
        }
        DEException.throwException(Translator.get("ee_dingtalk_robot_not_in_chat"));
    }

    @Override
    public void sendToUsers(Collection<String> platformUserIds, EePlatformMessage message) {
        DingtalkInfoVO config = requireActive();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(platformUserIds));
        ObjectNode msg = workMessage(config, message);
        for (int i = 0; i < ids.size(); i += 100) {
            ObjectNode body = EeJson.object();
            body.put("agent_id", Long.parseLong(config.getAgentId().trim()));
            body.put("userid_list", String.join(",", ids.subList(i, Math.min(ids.size(), i + 100))));
            body.set("msg", msg);
            call(http.postJson(EeHttp.url(oapi + "/topapi/message/corpconversation/asyncsend_v2", Map.of("access_token", accessToken(config))),
                    body, null), "asyncsend_v2");
        }
    }

    @Override
    public void sendToGroups(Collection<String> groupIds, EePlatformMessage message) {
        DingtalkInfoVO config = requireActive();
        String msgKey;
        ObjectNode param = EeJson.object();
        switch (message.getType()) {
            case TEXT -> {
                msgKey = "sampleText";
                param.put("content", message.getContent());
            }
            case MARKDOWN -> {
                msgKey = "sampleMarkdown";
                param.put("title", StringUtils.defaultIfBlank(message.getTitle(), "DataEase"));
                param.put("text", message.getContent());
            }
            case IMAGE -> {
                msgKey = "sampleImageMsg";
                param.put("photoURL", upload(config, "image", message));
            }
            default -> {
                msgKey = "sampleFile";
                param.put("mediaId", upload(config, "file", message));
                param.put("fileName", StringUtils.defaultIfBlank(message.getFileName(), "file"));
                param.put("fileType", StringUtils.defaultIfBlank(StringUtils.substringAfterLast(message.getFileName(), "."), "file"));
            }
        }
        for (String groupId : new LinkedHashSet<>(groupIds)) {
            ObjectNode body = EeJson.object();
            body.put("msgParam", param.toString());
            body.put("msgKey", msgKey);
            body.put("openConversationId", groupId);
            body.put("robotCode", robotCode(config));
            EeHttp.Response response = http.postJson(api + "/v1.0/robot/groupMessages/send", body,
                    EeHttp.headers("x-acs-dingtalk-access-token", accessToken(config)));
            if (!response.ok()) {
                throw new PlatformApiException("DingTalk groupMessages/send: " + StringUtils.abbreviate(response.body(), 300));
            }
        }
    }

    private ObjectNode workMessage(DingtalkInfoVO config, EePlatformMessage message) {
        ObjectNode msg = EeJson.object();
        switch (message.getType()) {
            case TEXT -> {
                msg.put("msgtype", "text");
                msg.putObject("text").put("content", message.getContent());
            }
            case MARKDOWN -> {
                msg.put("msgtype", "markdown");
                msg.putObject("markdown").put("title", StringUtils.defaultIfBlank(message.getTitle(), "DataEase")).put("text", message.getContent());
            }
            case IMAGE -> {
                msg.put("msgtype", "image");
                msg.putObject("image").put("media_id", upload(config, "image", message));
            }
            default -> {
                msg.put("msgtype", "file");
                msg.putObject("file").put("media_id", upload(config, "file", message));
            }
        }
        return msg;
    }

    private String upload(DingtalkInfoVO config, String type, EePlatformMessage message) {
        JsonNode res = call(http.postMultipart(EeHttp.url(oapi + "/media/upload", Map.of("access_token", accessToken(config), "type", type)),
                null, "media", StringUtils.defaultIfBlank(message.getFileName(), type), message.getData(), null, null), "media/upload");
        String mediaId = EeJson.text(res, "media_id");
        if (StringUtils.isBlank(mediaId)) {
            throw new PlatformApiException("DingTalk media/upload: media_id missing");
        }
        return mediaId;
    }

    private String robotCode(DingtalkInfoVO config) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(config.getRobotCode()), config.getAppKey());
    }

    String accessToken(DingtalkInfoVO config) {
        return cachedToken("access", config.getAppKey(), config.getAppSecret(), () -> {
            JsonNode res = call(http.get(EeHttp.url(oapi + "/gettoken", Map.of("appkey", config.getAppKey(), "appsecret", config.getAppSecret())), null),
                    "gettoken");
            String token = EeJson.text(res, "access_token");
            if (StringUtils.isBlank(token)) {
                throw new PlatformApiException("DingTalk gettoken: access_token missing");
            }
            return token;
        });
    }

    /**
     * 钉钉旧版接口：errcode 非 0 视为失败
     */
    private JsonNode call(EeHttp.Response response, String api) {
        JsonNode json;
        try {
            json = response.json();
        } catch (Exception e) {
            throw new PlatformApiException("DingTalk " + api + ": HTTP " + response.status());
        }
        String errcode = EeJson.text(json, "errcode");
        if (!response.ok() || (errcode != null && !"0".equals(errcode))) {
            throw new PlatformApiException("DingTalk " + api + ": " + StringUtils.defaultIfBlank(EeJson.text(json, "errmsg"),
                    StringUtils.defaultIfBlank(EeJson.text(json, "message"), "HTTP " + response.status())) + (errcode == null ? "" : " (" + errcode + ")"));
        }
        return json;
    }

    private String decodeUrl(String url) {
        try {
            return java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return url;
        }
    }
}
