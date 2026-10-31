package io.dataease.ee.init;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.permissions.dao.entity.*;
import io.dataease.ee.permissions.dao.repo.PerUserOrgRepository;
import io.dataease.initSql.Version;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 多组织成员关系（per_user_org）的数据迁移：为已有数据补齐成员关系。
 * <ul>
 *     <li>每个用户的主组织 per_user.default_oid；</li>
 *     <li>per_user_role 中出现的每个 (uid, oid)。</li>
 * </ul>
 * 已存在的成员关系跳过，可重复执行。只使用 JPA/QueryDSL，兼容全部元数据库。
 */
@Component
public class EeMultiOrgInit implements EeSqlBlock {

    private final JPAQueryFactory queryFactory;

    private final PerUserOrgRepository perUserOrgRepository;

    public EeMultiOrgInit(JPAQueryFactory queryFactory, PerUserOrgRepository perUserOrgRepository) {
        this.queryFactory = queryFactory;
        this.perUserOrgRepository = perUserOrgRepository;
    }

    @Override
    public Version getVersion() {
        return new Version("3.1.1");
    }

    @Override
    @Transactional
    public void execute() {
        QPerOrg o = QPerOrg.perOrg;
        QPerUser u = QPerUser.perUser;
        QPerUserRole ur = QPerUserRole.perUserRole;
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        Set<Long> orgIds = new HashSet<>(queryFactory.select(o.id).from(o).fetch());
        Set<Long> userIds = new HashSet<>(queryFactory.select(u.id).from(u).fetch());
        Set<String> existing = new HashSet<>();
        for (Tuple t : queryFactory.select(m.uid, m.oid).from(m).fetch()) {
            existing.add(t.get(m.uid) + ":" + t.get(m.oid));
        }
        // 主组织在前，保证按加入时间排序时主组织为最早加入的组织
        Map<String, Long[]> pending = new LinkedHashMap<>();
        for (Tuple t : queryFactory.select(u.id, u.defaultOid).from(u).where(u.defaultOid.isNotNull()).fetch()) {
            pending.putIfAbsent(t.get(u.id) + ":" + t.get(u.defaultOid), new Long[]{t.get(u.id), t.get(u.defaultOid)});
        }
        for (Tuple t : queryFactory.select(ur.uid, ur.oid).distinct().from(ur).fetch()) {
            pending.putIfAbsent(t.get(ur.uid) + ":" + t.get(ur.oid), new Long[]{t.get(ur.uid), t.get(ur.oid)});
        }
        long now = System.currentTimeMillis();
        int added = 0;
        for (Map.Entry<String, Long[]> entry : pending.entrySet()) {
            Long uid = entry.getValue()[0];
            Long oid = entry.getValue()[1];
            if (existing.contains(entry.getKey()) || !userIds.contains(uid) || !orgIds.contains(oid)) {
                continue;
            }
            PerUserOrg item = new PerUserOrg();
            item.setId(IDUtils.snowID());
            item.setUid(uid);
            item.setOid(oid);
            item.setCreateTime(now + added);
            perUserOrgRepository.save(item);
            existing.add(entry.getKey());
            added++;
        }
        LogUtil.info("per_user_org backfill: " + added + " membership(s) added");
    }
}
