package io.dataease.ee.setting.apikey;

import io.dataease.api.permissions.apikey.api.ApiKeyApi;
import io.dataease.api.permissions.apikey.dto.ApikeyEnableEditor;
import io.dataease.api.permissions.apikey.vo.ApiKeyVO;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的 API Key（个人中心）。以 API Key / 嵌入式 token 身份调用时不允许管理 API Key。
 */
@RestController("apiKeyServer")
@RequestMapping("/apikey")
public class ApiKeyServer implements ApiKeyApi {

    private final EeApiKeyManage apiKeyManage;

    public ApiKeyServer(EeApiKeyManage apiKeyManage) {
        this.apiKeyManage = apiKeyManage;
    }

    private Long uid() {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException(ResultCode.USER_NOT_LOGGED_IN.code(), ResultCode.USER_NOT_LOGGED_IN.message());
        }
        String authType = EeSettingSupport.currentAuthType();
        if (StringUtils.equalsAny(authType, EeSettingConstants.AUTH_TYPE_API_KEY, EeSettingConstants.AUTH_TYPE_EMBEDDED)) {
            DEException.throwException(Translator.get("ee_settings.apikey_forbidden"));
        }
        return uid;
    }

    @Override
    public void generate() {
        apiKeyManage.generate(uid());
    }

    @Override
    public List<ApiKeyVO> query() {
        return apiKeyManage.list(uid());
    }

    @Override
    public void switchEnable(ApikeyEnableEditor editor) {
        apiKeyManage.switchEnable(uid(), editor.getId(), Boolean.TRUE.equals(editor.getEnable()));
    }

    @Override
    public void delete(Long id) {
        apiKeyManage.delete(uid(), id);
    }
}
