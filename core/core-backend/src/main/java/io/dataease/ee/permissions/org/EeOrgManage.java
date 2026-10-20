package io.dataease.ee.permissions.org;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerOrg;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.dao.repo.PerOrgRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 组织基础查询：成员关系、组织树路径。
 */
@Component
public class EeOrgManage {

    private final JPAQueryFactory queryFactory;

    private final PerOrgRepository perOrgRepository;

    private final PerUserRepository perUserRepository;

    public EeOrgManage(JPAQueryFactory queryFactory, PerOrgRepository perOrgRepository, PerUserRepository perUserRepository) {
        this.queryFactory = queryFactory;
        this.perOrgRepository = perOrgRepository;
        this.perUserRepository = perUserRepository;
    }

    public boolean exists(Long oid) {
        return oid != null && perOrgRepository.existsById(oid);
    }

    public PerOrg get(Long oid) {
        return oid == null ? null : perOrgRepository.findById(oid).orElse(null);
    }

    /**
     * 用户是否属于组织。每个用户至多属于一个组织（per_user.default_oid）
     */
    public boolean isMember(Long uid, Long oid) {
        if (uid == null || oid == null) {
            return false;
        }
        PerUser user = perUserRepository.findById(uid).orElse(null);
        return user != null && oid.equals(user.getDefaultOid());
    }

    /**
     * 用户所属组织，无组织时为 null
     */
    public Long resolveCurrentOid(Long uid) {
        PerUser user = uid == null ? null : perUserRepository.findById(uid).orElse(null);
        if (user == null || user.getDefaultOid() == null || !exists(user.getDefaultOid())) {
            return null;
        }
        return user.getDefaultOid();
    }

    /**
     * 组织成员ID
     */
    public List<Long> memberIds(Long oid) {
        QPerUser u = QPerUser.perUser;
        return queryFactory.select(u.id).from(u).where(u.defaultOid.eq(oid)).fetch();
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
