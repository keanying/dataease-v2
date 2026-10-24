package io.dataease.ee.setting.apikey;

import io.dataease.api.permissions.apikey.vo.ApiKeyVO;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingSupport;
import io.dataease.ee.setting.apikey.dao.PerApiKey;
import io.dataease.ee.setting.apikey.dao.PerApiKeyRepository;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.utils.IDUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户 API Key 管理（每个用户最多 {@link EeSettingConstants#API_KEY_LIMIT} 个）
 */
@Component
public class EeApiKeyManage {

    private final PerApiKeyRepository repository;

    public EeApiKeyManage(PerApiKeyRepository repository) {
        this.repository = repository;
    }

    public List<ApiKeyVO> list(Long uid) {
        return repository.findByUidOrderByCreateTimeAsc(uid).stream().map(this::convert).toList();
    }

    @Transactional
    public synchronized void generate(Long uid) {
        if (repository.countByUid(uid) >= EeSettingConstants.API_KEY_LIMIT) {
            DEException.throwException(Translator.get("ee_settings.apikey_limit", EeSettingConstants.API_KEY_LIMIT));
        }
        PerApiKey key = new PerApiKey();
        key.setId(IDUtils.snowID());
        key.setUid(uid);
        String accessKey;
        do {
            accessKey = EeSettingSupport.randomAlphanumeric(16);
        } while (repository.findByAccessKey(accessKey).isPresent());
        key.setAccessKey(accessKey);
        key.setAccessSecret(EeSettingSupport.randomAlphanumeric(32));
        key.setEnable(true);
        key.setCreateTime(System.currentTimeMillis());
        repository.save(key);
    }

    @Transactional
    public void switchEnable(Long uid, Long id, boolean enable) {
        PerApiKey key = own(uid, id);
        key.setEnable(enable);
        repository.save(key);
    }

    @Transactional
    public void delete(Long uid, Long id) {
        repository.delete(own(uid, id));
    }

    public Optional<PerApiKey> findByAccessKey(String accessKey) {
        return repository.findByAccessKey(accessKey);
    }

    private PerApiKey own(Long uid, Long id) {
        PerApiKey key = id == null ? null : repository.findById(id).orElse(null);
        if (key == null || !Objects.equals(key.getUid(), uid)) {
            DEException.throwException(Translator.get("ee_settings.apikey_not_exist"));
        }
        return key;
    }

    private ApiKeyVO convert(PerApiKey po) {
        ApiKeyVO vo = new ApiKeyVO();
        vo.setId(po.getId());
        vo.setAccessKey(po.getAccessKey());
        vo.setAccessSecret(po.getAccessSecret());
        vo.setEnable(po.getEnable());
        vo.setCreateTime(po.getCreateTime());
        return vo;
    }
}
