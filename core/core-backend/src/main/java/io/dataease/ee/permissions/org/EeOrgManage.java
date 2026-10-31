package io.dataease.ee.permissions.org;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.*;
import io.dataease.ee.permissions.dao.repo.PerOrgRepository;
import io.dataease.ee.permissions.dao.repo.PerUserOrgRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.utils.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 组织基础查询：成员关系（per_user_org，一个用户可属于多个组织）、组织树路径。
 */
@Component
public class EeOrgManage {

    private final JPAQueryFactory queryFactory;

    private final PerOrgRepository perOrgRepository;

    private final PerUserRepository perUserRepository;

    private final PerUserOrgRepository perUserOrgRepository;

    public EeOrgManage(JPAQueryFactory queryFactory, PerOrgRepository perOrgRepository, PerUserRepository perUserRepository,
                       PerUserOrgRepository perUserOrgRepository) {
        this.queryFactory = queryFactory;
        this.perOrgRepository = perOrgRepository;
        this.perUserRepository = perUserRepository;
        this.perUserOrgRepository = perUserOrgRepository;
    }

    public boolean exists(Long oid) {
        return oid != null && perOrgRepository.existsById(oid);
    }

    public PerOrg get(Long oid) {
        return oid == null ? null : perOrgRepository.findById(oid).orElse(null);
    }

    /**
     * 用户是否属于组织（per_user_org 中存在成员关系）
     */
    public boolean isMember(Long uid, Long oid) {
        if (uid == null || oid == null) {
            return false;
        }
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        return queryFactory.selectOne().from(m).where(m.uid.eq(uid), m.oid.eq(oid)).fetchFirst() != null;
    }

    /**
     * 用户所属的全部组织ID，按加入时间排序
     */
    public List<Long> memberOids(Long uid) {
        if (uid == null) {
            return new ArrayList<>();
        }
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        return queryFactory.select(m.oid).from(m).where(m.uid.eq(uid)).orderBy(m.createTime.asc(), m.id.asc()).fetch();
    }

    /**
     * 批量查询用户所属组织：uid -> 组织ID（按加入时间排序）
     */
    public Map<Long, List<Long>> memberOidsOfUsers(Collection<Long> uids) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (uids == null || uids.isEmpty()) {
            return result;
        }
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        for (PerUserOrg item : queryFactory.selectFrom(m).where(m.uid.in(uids)).orderBy(m.createTime.asc(), m.id.asc()).fetch()) {
            result.computeIfAbsent(item.getUid(), key -> new ArrayList<>()).add(item.getOid());
        }
        return result;
    }

    /**
     * 组织成员ID
     */
    public List<Long> memberIds(Long oid) {
        if (oid == null) {
            return new ArrayList<>();
        }
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        return queryFactory.select(m.uid).from(m).where(m.oid.eq(oid)).fetch();
    }

    /**
     * 多个组织的成员ID（去重）
     */
    public Set<Long> memberIds(Collection<Long> oids) {
        if (oids == null || oids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        return new LinkedHashSet<>(queryFactory.select(m.uid).from(m).where(m.oid.in(oids)).fetch());
    }

    /**
     * 查询条件：用户 path 属于组织 oid
     */
    public BooleanExpression memberOf(NumberPath<Long> uidPath, Long oid) {
        QPerUserOrg m = new QPerUserOrg("memberOf");
        return JPAExpressions.selectOne().from(m).where(m.uid.eq(uidPath), m.oid.eq(oid)).exists();
    }

    /**
     * 查询条件：用户 path 属于 oids 中任一组织
     */
    public BooleanExpression memberOfAny(NumberPath<Long> uidPath, Collection<Long> oids) {
        QPerUserOrg m = new QPerUserOrg("memberOfAny");
        return JPAExpressions.selectOne().from(m).where(m.uid.eq(uidPath), m.oid.in(oids)).exists();
    }

    /**
     * 查询条件：用户 path 不属于任何组织
     */
    public BooleanExpression memberOfNone(NumberPath<Long> uidPath) {
        QPerUserOrg m = new QPerUserOrg("memberOfNone");
        return JPAExpressions.selectOne().from(m).where(m.uid.eq(uidPath)).notExists();
    }

    /**
     * 加入组织（已是成员时不变）
     *
     * @return 是否新加入
     */
    @Transactional
    public boolean addMember(Long uid, Long oid) {
        if (uid == null || oid == null || isMember(uid, oid)) {
            return false;
        }
        PerUserOrg item = new PerUserOrg();
        item.setId(IDUtils.snowID());
        item.setUid(uid);
        item.setOid(oid);
        item.setCreateTime(System.currentTimeMillis());
        perUserOrgRepository.save(item);
        return true;
    }

    @Transactional
    public void removeMember(Long uid, Long oid) {
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        queryFactory.delete(m).where(m.uid.eq(uid), m.oid.eq(oid)).execute();
    }

    @Transactional
    public void removeUser(Long uid) {
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        queryFactory.delete(m).where(m.uid.eq(uid)).execute();
    }

    @Transactional
    public void removeOrg(Long oid) {
        QPerUserOrg m = QPerUserOrg.perUserOrg;
        queryFactory.delete(m).where(m.oid.eq(oid)).execute();
    }

    /**
     * 用户的主组织：default_oid（仍是成员时），否则最早加入的组织；不属于任何组织时为 null
     */
    public Long homeOid(PerUser user) {
        if (user == null) {
            return null;
        }
        List<Long> oids = memberOids(user.getId());
        if (user.getDefaultOid() != null && oids.contains(user.getDefaultOid())) {
            return user.getDefaultOid();
        }
        return oids.isEmpty() ? null : oids.getFirst();
    }

    /**
     * 登录后的当前组织：最近切换的组织（仍是成员时）> 主组织 > 最早加入的组织；不属于任何组织时为 null。
     * 系统管理员可以切换到任意组织，最近切换的组织只要存在即有效。
     */
    public Long resolveCurrentOid(Long uid) {
        PerUser user = uid == null ? null : perUserRepository.findById(uid).orElse(null);
        if (user == null) {
            return null;
        }
        List<Long> oids = memberOids(uid);
        Long last = user.getLastOid();
        if (last != null && (oids.contains(last) || (EeConstants.ADMIN_UID.equals(uid) && exists(last)))) {
            return last;
        }
        if (user.getDefaultOid() != null && oids.contains(user.getDefaultOid())) {
            return user.getDefaultOid();
        }
        return oids.isEmpty() ? null : oids.getFirst();
    }

    /**
     * token 中的组织对该用户是否仍然有效：成员关系仍存在（系统管理员为组织仍存在）；
     * token 不含组织时，要求用户当前确实不属于任何组织。
     */
    public boolean validTokenOid(Long uid, Long tokenOid) {
        if (tokenOid == null) {
            return resolveCurrentOid(uid) == null;
        }
        if (EeConstants.ADMIN_UID.equals(uid)) {
            return exists(tokenOid);
        }
        return isMember(uid, tokenOid);
    }

    /**
     * 组织及其全部下级组织ID
     */
    public List<Long> selfAndChildren(Long oid) {
        PerOrg org = get(oid);
        if (org == null) {
            return Collections.emptyList();
        }
        QPerOrg qOrg = QPerOrg.perOrg;
        String prefix = org.getRootPath() + ",";
        List<Long> ids = new ArrayList<>(queryFactory.select(qOrg.id).from(qOrg).where(qOrg.rootPath.startsWith(prefix)).fetch());
        ids.add(0, oid);
        return ids;
    }

    /**
     * 组织的祖先ID（不含自身），由近到远
     */
    public List<Long> ancestors(Long oid) {
        PerOrg org = get(oid);
        if (org == null || StringUtils.isBlank(org.getRootPath())) {
            return Collections.emptyList();
        }
        List<Long> path = new ArrayList<>(Arrays.stream(org.getRootPath().split(",")).filter(StringUtils::isNotBlank).map(Long::valueOf).toList());
        path.remove(oid);
        Collections.reverse(path);
        return path;
    }

    public String buildRootPath(Long pid, Long id) {
        if (pid == null || pid == 0L) {
            return String.valueOf(id);
        }
        PerOrg parent = get(pid);
        String parentPath = parent == null || StringUtils.isBlank(parent.getRootPath()) ? String.valueOf(pid) : parent.getRootPath();
        return parentPath + "," + id;
    }
}
