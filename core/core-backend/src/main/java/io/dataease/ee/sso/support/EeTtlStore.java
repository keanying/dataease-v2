package io.dataease.ee.sso.support;

import io.dataease.utils.CacheUtils;
import io.dataease.utils.LogUtil;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 带过期时间的键值存储：优先使用系统缓存（JCache / Redis，集群共享），缓存不可用时退化为进程内存。
 *
 * <p>同一缓存名称必须使用相同的过期时间（JCache 在创建缓存时确定过期策略）。</p>
 */
@Component
public class EeTtlStore {

    private final Map<String, Entry> local = new ConcurrentHashMap<>();

    public void put(String cache, String key, String value, long minutes) {
        try {
            CacheUtils.put(cache, key, value, minutes, TimeUnit.MINUTES);
            return;
        } catch (Throwable e) {
            LogUtil.debug("cache unavailable, fallback to memory: " + e.getMessage());
        }
        cleanup();
        local.put(cache + ":" + key, new Entry(value, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes)));
    }

    public String get(String cache, String key) {
        try {
            Object value = CacheUtils.get(cache, key);
            if (value != null) {
                return value.toString();
            }
        } catch (Throwable e) {
            LogUtil.debug("cache unavailable, fallback to memory: " + e.getMessage());
        }
        Entry entry = local.get(cache + ":" + key);
        if (entry == null || entry.expireAt < System.currentTimeMillis()) {
            return null;
        }
        return entry.value;
    }

    public void remove(String cache, String key) {
        try {
            CacheUtils.keyRemove(cache, key);
        } catch (Throwable e) {
            LogUtil.debug("cache unavailable: " + e.getMessage());
        }
        local.remove(cache + ":" + key);
    }

    /**
     * 原子性不强的“首次使用”标记：返回 true 表示此前未使用过
     */
    public synchronized boolean markOnce(String cache, String key, long minutes) {
        if (get(cache, key) != null) {
            return false;
        }
        put(cache, key, "1", minutes);
        return true;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(e -> e.getValue().expireAt < now);
    }

    private record Entry(String value, long expireAt) {
    }
}
