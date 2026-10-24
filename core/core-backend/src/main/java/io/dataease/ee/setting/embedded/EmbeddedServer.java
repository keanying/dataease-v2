package io.dataease.ee.setting.embedded;

import io.dataease.api.permissions.embedded.api.EmbeddedApi;
import io.dataease.api.permissions.embedded.dto.EmbeddedCreator;
import io.dataease.api.permissions.embedded.dto.EmbeddedEditor;
import io.dataease.api.permissions.embedded.dto.EmbeddedOrigin;
import io.dataease.api.permissions.embedded.dto.EmbeddedResetRequest;
import io.dataease.api.permissions.embedded.vo.EmbeddedGridVO;
import io.dataease.constant.AuthConstant;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.KeywordRequest;
import io.dataease.result.PageResult;
import io.dataease.utils.RsaUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 嵌入式应用管理（仅系统管理员）与嵌入式运行时接口
 */
@RestController("embeddedServer")
@RequestMapping("/embedded")
public class EmbeddedServer implements EmbeddedApi {

    private final EeEmbeddedManage embeddedManage;

    public EmbeddedServer(EeEmbeddedManage embeddedManage) {
        this.embeddedManage = embeddedManage;
    }

    @Override
    public PageResult<EmbeddedGridVO> queryGrid(int goPage, int pageSize, KeywordRequest request) {
        EeSettingSupport.checkSysAdmin();
        return embeddedManage.pager(goPage, pageSize, request == null ? null : request.getKeyword());
    }

    @Override
    public void create(EmbeddedCreator creator) {
        EeSettingSupport.checkSysAdmin();
        embeddedManage.create(creator);
    }

    @Override
    public void edit(EmbeddedEditor editor) {
        EeSettingSupport.checkSysAdmin();
        embeddedManage.edit(editor);
    }

    @Override
    public void delete(Long id) {
        EeSettingSupport.checkSysAdmin();
        embeddedManage.delete(Collections.singletonList(id));
    }

    @Override
    public void batchDelete(List<Long> ids) {
        EeSettingSupport.checkSysAdmin();
        embeddedManage.delete(ids);
    }

    @Override
    public void reset(EmbeddedResetRequest request) {
        EeSettingSupport.checkSysAdmin();
        embeddedManage.reset(request);
    }

    @Override
    public List<String> domainList() {
        EeSettingSupport.checkSysAdmin();
        return embeddedManage.domainList();
    }

    @Override
    public void initIframe(EmbeddedOrigin origin) {
        if (origin == null || StringUtils.isAnyBlank(origin.getToken(), origin.getOrigin())) {
            DEException.throwException(Translator.get("ee_settings.embedded_origin_mismatch"));
        }
        EeEmbeddedManage.Verified verified = embeddedManage.verify(origin.getToken());
        String originText;
        try {
            String decrypted = RsaUtils.decryptStr(origin.getOrigin());
            originText = new String(Base64.getDecoder().decode(StringUtils.trimToEmpty(decrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_settings.embedded_origin_mismatch"));
            return;
        }
        if (!embeddedManage.originAllowed(verified.app(), originText)) {
            DEException.throwException(Translator.get("ee_settings.embedded_origin_mismatch"));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTokenArgs() {
        HttpServletRequest request = EeSettingSupport.currentRequest();
        if (request == null) {
            return new LinkedHashMap<>();
        }
        Object claims = request.getAttribute(EeSettingConstants.EMBEDDED_CLAIMS_ATTR);
        if (!(claims instanceof Map<?, ?>)) {
            return new LinkedHashMap<>();
        }
        return embeddedManage.outerParams((Map<String, Object>) claims);
    }

    @Override
    public int getLimitCount() {
        return embeddedManage.limit();
    }

    @Override
    public void logout() {
        HttpServletRequest request = EeSettingSupport.currentRequest();
        if (request != null && StringUtils.equals(EeSettingSupport.currentAuthType(), EeSettingConstants.AUTH_TYPE_EMBEDDED)) {
            embeddedManage.destroy(request.getHeader(AuthConstant.EMBEDDED_TOKEN_KEY));
        }
    }
}
