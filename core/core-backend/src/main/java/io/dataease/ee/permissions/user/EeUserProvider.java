package io.dataease.ee.permissions.user;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.spi.V3UserProvider;
import io.dataease.permission.util.V3UserUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向 {@link V3UserUtil} 提供用户信息，带短时本地缓存，用户变更时由 {@link #evict(Long)} 失效。
 */
@Component
public class EeUserProvider implements V3UserProvider {

    private static final long TTL_MILLIS = 60_000L;

    private final PerUserRepository perUserRepository;

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public EeUserProvider(PerUserRepository perUserRepository) {
        this.perUserRepository = perUserRepository;
    }

    @PostConstruct
    public void register() {
        V3UserUtil.registerProvider(this);
    }

    @Override
    public V3BaseUser getUser(Long uid) {
        if (uid == null) {
            return null;
        }
        Entry entry = cache.get(uid);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expireAt > now) {
            return copy(entry.user);
        }
        V3BaseUser user = perUserRepository.findById(uid).map(this::convert).orElse(null);
        if (user != null) {
            cache.put(uid, new Entry(user, now + TTL_MILLIS));
        } else {
            cache.remove(uid);
        }
        return copy(user);
    }

    @Override
    public boolean isSysAdmin(Long uid) {
        return EeConstants.ADMIN_UID.equals(uid);
    }

    public void evict(Long uid) {
        if (uid != null) {
            cache.remove(uid);
        }
    }

    public void evictAll() {
        cache.clear();
    }

    private V3BaseUser convert(PerUser po) {
        V3BaseUser user = new V3BaseUser();
        user.setId(po.getId());
        user.setAccount(po.getAccount());
        user.setName(po.getName());
        user.setEmail(po.getEmail());
        user.setPhone(po.getPhone());
        user.setDefaultOid(po.getDefaultOid());
        user.setOid(po.getDefaultOid());
        user.setPwd(po.getPwd());
        user.setEnable(po.getEnable());
        user.setOrigin(po.getOrigin());
        user.setLanguage(po.getLanguage());
        return user;
    }

    private V3BaseUser copy(V3BaseUser source) {
        if (source == null) {
            return null;
        }
        V3BaseUser target = new V3BaseUser();
        target.setId(source.getId());
        target.setAccount(source.getAccount());
        target.setName(source.getName());
        target.setEmail(source.getEmail());
        target.setPhone(source.getPhone());
        target.setDefaultOid(source.getDefaultOid());
        target.setOid(source.getOid());
        target.setPwd(source.getPwd());
        target.setEnable(source.getEnable());
        target.setOrigin(source.getOrigin());
        target.setLanguage(source.getLanguage());
        return target;
    }

    private record Entry(V3BaseUser user, long expireAt) {
    }
}
