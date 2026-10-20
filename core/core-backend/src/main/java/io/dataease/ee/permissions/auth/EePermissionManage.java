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
 *     <li>系统管理员、当前组织管理员（拥有组织内置的 typeCode=9 角色）拥有组织内全部资源的全部权限；</li>
 *     <li>组织内置角色对组织内全部资源有隐式授权：数据分析师(7) 为管理，普通用户(0) 为查看（数据源为使用），
 *     导出位为仪表板/大屏 111、数据集 1；</li>
 *     <li>其余为显式授权：用户直接授权 ∪ 其在当前组织内全部角色的授权，按等级取最大、导出位取并集；</li>
 *     <li>资源ID 0 表示根目录，对其拥有管理权限才能在根目录下创建资源；</li>
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
     * 用户在组织内内置角色的最高类型；系统管理员视为 9；没有内置角色返回 -1
     */
    public int builtinRoleType(Long uid, Long oid) {
        if (isSysAdmin(uid)) {
            return EeConstants.ROLE_TYPE_ADMIN;
        }
        return roles(uid, oid).stream().filter(role -> Boolean.TRUE.equals(role.getRoot()))
                .map(PerRole::getTypeCode).filter(Objects::nonNull).max(Integer::compareTo).orElse(-1);
    }

    public boolean isOrgAdmin(Long uid, Long oid) {
        return builtinRoleType(uid, oid) >= EeConstants.ROLE_TYPE_ADMIN;
    }

    /**
     * 是否可以在根目录下创建资源：组织管理员、内置数据分析师，或被显式授予根目录（ID 0）的管理权限
     */
    public boolean canManageRoot(Long uid, Long oid) {
        return canManageRoot(uid, oid, null);
    }

    public boolean canManageRoot(Long uid, Long oid, Integer rtId) {
        if (builtinRoleType(uid, oid) >= EeConstants.ROLE_TYPE_ANALYST) {
            return true;
        }
        if (rtId == null) {
            return false;
        }
        return explicit(uid, oid, rtId).getOrDefault(0L, Perm.NONE).satisfies(EeConstants.WEIGHT_MANAGE);
    }

    /**
     * 内置角色的隐式权限
     */
    public static Perm builtinPerm(int typeCode, int rtId) {
        if (typeCode >= EeConstants.ROLE_TYPE_ADMIN) {
            return Perm.FULL;
        }
        int ext = switch (rtId) {
            case EeConstants.RT_PANEL, EeConstants.RT_SCREEN -> EeConstants.EXT_ALL;
            case EeConstants.RT_DATASET -> 1;
            default -> 0;
        };
        int base = rtId == EeConstants.RT_DATASOURCE ? EeConstants.WEIGHT_USE : EeConstants.WEIGHT_READ;
        if (typeCode >= EeConstants.ROLE_TYPE_ANALYST) {
            return new Perm(Math.max(EeConstants.WEIGHT_MANAGE, base), ext);
        }
        if (typeCode >= EeConstants.ROLE_TYPE_STAFF) {
            return new Perm(base, ext);
        }
        return Perm.NONE;
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
     * 用户 + 其组织内角色 的显式授权
     */
    public Map<Long, Perm> explicit(Long uid, Long oid, int rtId) {
        Map<Long, Perm> result = new HashMap<>(grants(EeConstants.SUBJECT_USER, List.of(uid), rtId));
        grants(EeConstants.SUBJECT_ROLE, roleIds(uid, oid), rtId).forEach((id, perm) -> result.merge(id, perm, Perm::merge));
        return result;
    }

    /**
     * 用户在组织内对某类资源的全部有效权限（仅包含有权限的资源，不含根目录）
     */
    public Map<Long, Perm> effective(Long uid, Long oid, int rtId) {
        if (uid == null || oid == null) {
            return Collections.emptyMap();
        }
        Set<Long> orgResources = busiResourceManage.idsByOrg(rtId, oid);
        Map<Long, Perm> result = new HashMap<>();
        int builtin = builtinRoleType(uid, oid);
        Perm builtinPerm = builtin < 0 ? Perm.NONE : builtinPerm(builtin, rtId);
        if (!builtinPerm.isNone()) {
            orgResources.forEach(id -> result.put(id, builtinPerm));
            if (builtinPerm == Perm.FULL) {
                return result;
            }
        }
        explicit(uid, oid, rtId).forEach((id, perm) -> {
            if (orgResources.contains(id)) {
                result.merge(id, perm, Perm::merge);
            }
        });
        result.values().removeIf(Perm::isNone);
        return result;
    }

    /**
     * 当前身份对某资源的有效权限；resourceId 为 0 时表示根目录
     */
    public Perm effective(Long uid, Long oid, int rtId, Long resourceId) {
        if (uid == null || oid == null || resourceId == null) {
            return Perm.NONE;
        }
        if (resourceId <= 0L) {
            return canManageRoot(uid, oid, rtId) ? new Perm(EeConstants.WEIGHT_MANAGE, 0) : Perm.NONE;
        }
        PerBusiResource resource = busiResourceManage.get(resourceId);
        if (resource == null || !Objects.equals(resource.getOrgId(), oid) || !Objects.equals(resource.getRtId(), rtId)) {
            return Perm.NONE;
        }
        int builtin = builtinRoleType(uid, oid);
        Perm perm = builtin < 0 ? Perm.NONE : builtinPerm(builtin, rtId);
        if (perm == Perm.FULL) {
            return perm;
        }
        return perm.merge(explicit(uid, oid, rtId).get(resourceId));
    }

    public Perm current(int rtId, Long resourceId) {
        return effective(V3UserUtil.getUid(), V3UserUtil.getOid(), rtId, resourceId);
    }
}
