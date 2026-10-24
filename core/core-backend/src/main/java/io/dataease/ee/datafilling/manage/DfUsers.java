package io.dataease.ee.datafilling.manage;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.entity.QPerUserRole;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 填报模块使用的用户查询
 */
@Component
public class DfUsers {

    private final JPAQueryFactory queryFactory;

    public DfUsers(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Map<Long, PerUser> map(Collection<Long> uids) {
        Map<Long, PerUser> result = new HashMap<>();
        Set<Long> ids = new HashSet<>(uids == null ? Collections.emptyList() : uids);
        ids.remove(null);
        if (ids.isEmpty()) {
            return result;
        }
        QPerUser q = QPerUser.perUser;
        queryFactory.selectFrom(q).where(q.id.in(ids)).fetch().forEach(u -> result.put(u.getId(), u));
        return result;
    }

    public Map<Long, String> names(Collection<Long> uids) {
        Map<Long, String> result = new HashMap<>();
        map(uids).forEach((id, u) -> result.put(id, u.getName()));
        return result;
    }

    public String name(Long uid) {
        return uid == null ? null : names(List.of(uid)).get(uid);
    }

    /**
     * 组织内的启用用户（含以该组织为默认组织的用户）
     */
    public List<PerUser> orgUsers(Long oid) {
        QPerUser q = QPerUser.perUser;
        QPerUserRole ur = QPerUserRole.perUserRole;
        Set<Long> ids = new HashSet<>(queryFactory.select(ur.uid).from(ur).where(ur.oid.eq(oid)).fetch());
        ids.addAll(queryFactory.select(q.id).from(q).where(q.defaultOid.eq(oid)).fetch());
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        return queryFactory.selectFrom(q).where(q.id.in(ids), q.enable.isTrue()).fetch();
    }

    /**
     * 角色下的启用用户
     */
    public Set<Long> usersOfRoles(Collection<Long> rids, Long oid) {
        if (CollectionUtils.isEmpty(rids)) {
            return new HashSet<>();
        }
        QPerUserRole ur = QPerUserRole.perUserRole;
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.select(ur.uid).from(ur).innerJoin(q).on(q.id.eq(ur.uid))
                .where(ur.rid.in(rids), q.enable.isTrue());
        if (oid != null) {
            query.where(ur.oid.eq(oid));
        }
        return new HashSet<>(query.fetch());
    }

    public Set<Long> enabled(Collection<Long> uids) {
        if (CollectionUtils.isEmpty(uids)) {
            return new HashSet<>();
        }
        QPerUser q = QPerUser.perUser;
        return new HashSet<>(queryFactory.select(q.id).from(q).where(q.id.in(uids), q.enable.isTrue()).fetch());
    }
}
