package io.dataease.ee.sso;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.sso.dao.EeAuthConfig;
import io.dataease.ee.sso.dao.EeAuthConfigRepository;
import io.dataease.ee.sso.dao.QEeAuthConfig;
import io.dataease.ee.sso.support.EeJson;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.utils.RsaUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证设置与平台对接配置的读写（ee_auth_config）。配置内容整体对称加密存储。
 */
@Component
public class EeAuthConfigManage {

    private final EeAuthConfigRepository repository;

    private final JPAQueryFactory queryFactory;

    public EeAuthConfigManage(EeAuthConfigRepository repository, JPAQueryFactory queryFactory) {
        this.repository = repository;
        this.queryFactory = queryFactory;
    }

    public EeAuthConfig find(String name) {
        QEeAuthConfig q = QEeAuthConfig.eeAuthConfig;
        return queryFactory.selectFrom(q).where(q.name.eq(name)).fetchFirst();
    }

    public EeAuthConfig findById(Long id) {
        return id == null ? null : repository.findById(id).orElse(null);
    }

    public Map<String, EeAuthConfig> all() {
        Map<String, EeAuthConfig> map = new LinkedHashMap<>();
        List<EeAuthConfig> list = repository.findAll();
        for (EeAuthConfig item : list) {
            map.put(item.getName(), item);
        }
        return map;
    }

    /**
     * 读取配置，未配置时返回 null
     */
    public <T> T load(String name, Class<T> type) {
        return parse(find(name), type);
    }

    public <T> T parse(EeAuthConfig entity, Class<T> type) {
        if (entity == null || StringUtils.isBlank(entity.getConfig())) {
            return null;
        }
        try {
            return EeJson.read(RsaUtils.symmetricDecrypt(entity.getConfig()), type);
        } catch (Exception e) {
            LogUtil.error("failed to read auth config " + entity.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 已启用且最近一次校验通过
     */
    public boolean active(String name) {
        EeAuthConfig entity = find(name);
        return entity != null && Boolean.TRUE.equals(entity.getEnable()) && Boolean.TRUE.equals(entity.getValid());
    }

    /**
     * 读取已启用的配置，未启用时抛出“未启用”
     */
    public <T> T requireActive(String name, Class<T> type) {
        EeAuthConfig entity = find(name);
        T config = parse(entity, type);
        if (config == null || !Boolean.TRUE.equals(entity.getEnable())) {
            DEException.throwException(Translator.get("ee_platform_not_enabled"));
        }
        return config;
    }

    /**
     * 保存配置内容与状态
     *
     * @param valid  为 null 时保持不变
     * @param enable 为 null 时保持不变
     */
    @Transactional
    public EeAuthConfig save(String name, Object config, Boolean valid, Boolean enable) {
        EeAuthConfig entity = find(name);
        long now = System.currentTimeMillis();
        if (entity == null) {
            entity = new EeAuthConfig();
            entity.setId(IDUtils.snowID());
            entity.setName(name);
            entity.setCreateTime(now);
            entity.setEnable(false);
            entity.setValid(false);
        }
        entity.setConfig(RsaUtils.symmetricEncrypt(EeJson.write(config)));
        if (valid != null) {
            entity.setValid(valid);
        }
        if (enable != null) {
            entity.setEnable(enable);
        }
        if (!Boolean.TRUE.equals(entity.getValid())) {
            entity.setEnable(false);
        }
        entity.setUpdateTime(now);
        entity.setUpdateBy(V3UserUtil.getUid());
        return repository.save(entity);
    }

    /**
     * 更新状态位（QueryDSL 更新，不受非空合并切面影响）
     */
    @Transactional
    public void updateFlags(Long id, Boolean enable, Boolean valid) {
        QEeAuthConfig q = QEeAuthConfig.eeAuthConfig;
        var update = queryFactory.update(q).where(q.id.eq(id)).set(q.updateTime, System.currentTimeMillis());
        Boolean finalEnable = Boolean.FALSE.equals(valid) ? Boolean.FALSE : enable;
        if (finalEnable == null && valid == null) {
            return;
        }
        if (finalEnable != null) {
            update.set(q.enable, finalEnable);
        }
        if (valid != null) {
            update.set(q.valid, valid);
        }
        update.execute();
    }
}
