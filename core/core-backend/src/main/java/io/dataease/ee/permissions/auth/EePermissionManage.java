package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerAuth;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.QPerAuth;
import io.dataease.ee.permissions.dao.entity.QPerRole;
import io.dataease.ee.permissions.dao.entity.QPerUserRole;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 权限计算引擎。
 *
 * <ul>
 *     <li>系统管理员、当前组织管理员（组织内 typeCode=9 的角色）拥有组织内全部资源的全部权限；</li>
 *     <li>其他用户的权限 = 用户直接授权 ∪ 其在当前组织内全部角色的授权，按等级取最大、导出位取并集；</li>
 *     <li>资源必须属于当前组织（per_busi_resource.org_id）。</li>
 * </ul>
 */
@Component
public class EePermissionManage {

    private final JPAQueryFactory queryFactory;

    private final EeBusiResourceManage busiResourceManage;

    public EePermissionManage(JPAQueryFactory queryFactory, EeBusiResourceManage busiResourceManage) {
        this.queryFactory = queryFactory;
        this.busiResourceManage = busiResourceManage;
    }

    public boolean isSysAdmin(Long uid) {
        return V3UserUtil.isSysAdmin(uid);
    }

    /**
     * 用户在组织内的角色
     */
    public List<PerRole> roles(Long uid, Long oid) {
        if (uid == null || oid == null) {
            return Collections.emptyList();
        }
        QPerUserRole ur = QPerUserRole.perUserRole;
        QPerRole r = QPerRole.perRole;
        return queryFactory.select(r).from(r).innerJoin(ur).on(ur.rid.eq(r.id))
                .where(ur.uid.eq(uid), ur.oid.eq(oid)).fetch();
    }

    public List<Long> roleIds(Long uid, Long oid) {
        return roles(uid, oid).stream().map(PerRole::getId).toList();
    }

    /**
     * 用户在组织内的最高角色类型；系统管理员视为 9
     */
    public int maxRoleType(Long uid, Long oid) {
        if (isSysAdmin(uid)) {
            return EeConstants.ROLE_TYPE_ADMIN;
        }
        return roles(uid, oid).stream().map(PerRole::getTypeCode).filter(Objects::nonNull).max(Integer::compareTo).orElse(-1);
    }

    public boolean isOrgAdmin(Long uid, Long oid) {
        return maxRoleType(uid, oid) >= EeConstants.ROLE_TYPE_ADMIN;
    }

    /**
     * 是否可以在根目录下创建资源（组织管理员、数据分析师）
     */
    public boolean canManageRoot(Long uid, Long oid) {
        return maxRoleType(uid, oid) >= EeConstants.ROLE_TYPE_ANALYST;
    }

    /**
     * 主体的直接授权
     */
    public Map<Long, Perm> grants(int subjectType, Collection<Long> subjectIds, int rtId) {
        if (CollectionUtils.isEmpty(subjectIds)) {
            return Collections.emptyMap();
        }
        QPerAuth q = QPerAuth.perAuth;
        List<PerAuth> list = queryFactory.selectFrom(q)
                .where(q.subjectType.eq(subjectType), q.subjectId.in(subjectIds), q.rtId.eq(rtId)).fetch();
        Map<Long, Perm> result = new HashMap<>();
        for (PerAuth auth : list) {
            Perm perm = new Perm(auth.getWeight() == null ? 0 : auth.getWeight(), auth.getExt() == null ? 0 : auth.getExt());
            result.merge(auth.getResourceId(), perm, Perm::merge);
        }
        return result;
    }

    /**
     * 用户在组织内对某类资源的全部有效权限（仅包含有权限的资源）
     */
    public Map<Long, Perm> effective(Long uid, Long oid, int rtId) {
        if (uid == null || oid == null) {
            return Collections.emptyMap();
        }
        Set<Long> orgResources = busiResourceManage.idsByOrg(rtId, oid);
        Map<Long, Perm> result = new HashMap<>();
        if (isOrgAdmin(uid, oid)) {
            orgResources.forEach(id -> result.put(id, Perm.FULL));
            return result;
        }
        Map<Long, Perm> userGrants = grants(EeConstants.SUBJECT_USER, List.of(uid), rtId);
        Map<Long, Perm> roleGrants = grants(EeConstants.SUBJECT_ROLE, roleIds(uid, oid), rtId);
        userGrants.forEach((id, perm) -> {
            if (orgResources.contains(id)) {
                result.merge(id, perm, Perm::merge);
            }
        });
        roleGrants.forEach((id, perm) -> {
            if (orgResources.contains(id)) {
                result.merge(id, perm, Perm::merge);
            }
        });
        result.values().removeIf(Perm::isNone);
        return result;
    }

    /**
     * 当前身份对某资源的有效权限
     */
    public Perm effective(Long uid, Long oid, int rtId, Long resourceId) {
        if (uid == null || oid == null || resourceId == null) {
            return Perm.NONE;
        }
        if (resourceId == 0L) {
            return canManageRoot(uid, oid) ? new Perm(EeConstants.WEIGHT_MANAGE, 0) : Perm.NONE;
        }
        PerBusiResource resource = busiResourceManage.get(resourceId);
        if (resource == null || !Objects.equals(resource.getOrgId(), oid) || resource.getRtId() != rtId) {
            return Perm.NONE;
        }
        if (isOrgAdmin(uid, oid)) {
            return Perm.FULL;
        }
        Perm perm = Perm.NONE;
        perm = perm.merge(grants(EeConstants.SUBJECT_USER, List.of(uid), rtId).get(resourceId));
        Map<Long, Perm> roleGrants = grants(EeConstants.SUBJECT_ROLE, roleIds(uid, oid), rtId);
        perm = perm.merge(roleGrants.get(resourceId));
        return perm;
    }

    public Perm current(int rtId, Long resourceId) {
        return effective(V3UserUtil.getUid(), V3UserUtil.getOid(), rtId, resourceId);
    }
}
