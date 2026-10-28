package io.dataease.ee.auth.revoke;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.utils.Md5Utils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已注销 token 登记（登录 token 与嵌入式 token 共用）。
 *
 * <p>注销记录写入 {@code per_token_revoked}，重启后仍然有效；多节点部署时各节点每 30 秒同步其他节点新增的记录。
 * 校验只查本地缓存，不访问数据库。</p>
 */
@Component
public class EeTokenRevocation {

    private static final long SYNC_OVERLAP_MS = 60_000L;

    private final PerTokenRevokedRepository repository;

    private final JPAQueryFactory queryFactory;

    private final TransactionTemplate transactionTemplate;

    /**
     * 摘要 -> 过期时间
     */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    private volatile long lastSync = 0L;

    public EeTokenRevocation(PerTokenRevokedRepository repository, JPAQueryFactory queryFactory, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.queryFactory = queryFactory;
        this.transactionTemplate = transactionTemplate;
    }

    public static String digest(String token) {
        return Md5Utils.md5(token);
    }

    public boolean isRevoked(String token) {
        if (StringUtils.isBlank(token)) {
            return false;
        }
        Long exp = revoked.get(digest(token));
        return exp != null && exp >= System.currentTimeMillis();
    }

    /**
     * 注销 token
     *
     * @param expireTime token 本身的过期时间
     */
    public void revoke(String token, String tokenType, long expireTime) {
        if (StringUtils.isBlank(token)) {
            return;
        }
        String digest = digest(token);
        if (revoked.putIfAbsent(digest, expireTime) != null) {
            return;
        }
        try {
            QPerTokenRevoked q = QPerTokenRevoked.perTokenRevoked;
            boolean exists = queryFactory.selectOne().from(q).where(q.digest.eq(digest)).fetchFirst() != null;
            if (!exists) {
                PerTokenRevoked entity = new PerTokenRevoked();
                entity.setId(IDUtils.snowID());
                entity.setDigest(digest);
                entity.setTokenType(tokenType);
                entity.setExpireTime(expireTime);
                entity.setCreateTime(System.currentTimeMillis());
                repository.save(entity);
            }
        } catch (Exception e) {
            // 写库失败时本节点仍按已注销处理
            LogUtil.error("save revoked token failed: " + e.getMessage(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        sync();
    }

    /**
     * 同步其他节点新增的注销记录，并清理过期记录
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void sync() {
        long now = System.currentTimeMillis();
        try {
            QPerTokenRevoked q = QPerTokenRevoked.perTokenRevoked;
            long since = lastSync == 0L ? 0L : lastSync - SYNC_OVERLAP_MS;
            List<PerTokenRevoked> rows = queryFactory.selectFrom(q)
                    .where(q.createTime.goe(since), q.expireTime.goe(now))
                    .fetch();
            rows.forEach(row -> revoked.put(row.getDigest(), row.getExpireTime()));
            lastSync = now;
        } catch (Exception e) {
            LogUtil.error("sync revoked tokens failed: " + e.getMessage(), e);
        }
        revoked.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    @Scheduled(cron = "0 17 * * * ?")
    public void purge() {
        long now = System.currentTimeMillis();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                QPerTokenRevoked q = QPerTokenRevoked.perTokenRevoked;
                queryFactory.delete(q).where(q.expireTime.lt(now)).execute();
            });
        } catch (Exception e) {
            LogUtil.error("purge revoked tokens failed: " + e.getMessage(), e);
        }
    }
}
