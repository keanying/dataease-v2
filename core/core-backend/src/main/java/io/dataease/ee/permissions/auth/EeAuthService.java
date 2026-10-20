package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.auth.dto.*;
import io.dataease.api.permissions.auth.vo.*;
import io.dataease.dao.auto.entity.CoreDatasource;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.dao.auto.entity.QCoreDatasource;
import io.dataease.dao.auto.entity.QPerBusiResource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.*;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.ee.permissions.menu.EeMenuRegistry;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.vo.DatasourceConfiguration;
import io.dataease.i18n.Translator;
import io.dataease.menu.dao.auto.entity.CoreMenu;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限配置（按用户/角色配置、按资源配置）
 */
@Component
public class EeAuthService {

    private final JPAQueryFactory queryFactory;

    private final EePermissionManage permissionManage;

    private final EeGrantManage grantManage;

    private final EeMenuManage menuManage;

    private final EeOrgManage orgManage;

    private final EeRoleManage roleManage;

    public EeAuthService(JPAQueryFactory queryFactory, EePermissionManage permissionManage, EeGrantManage grantManage,
                         EeMenuManage menuManage, EeOrgManage orgManage, EeRoleManage roleManage) {
        this.queryFactory = queryFactory;
        this.permissionManage = permissionManage;
        this.grantManage = grantManage;
        this.menuManage = menuManage;
        this.orgManage = orgManage;
        this.roleManage = roleManage;
    }

    // ------------------------------------------------------------------ scope

    private boolean systemScope(boolean system) {
        return system && V3UserUtil.isSysAdmin() && !V3UserUtil.getProxy().isProxy();
    }

    /**
     * 本次配置涉及的组织
     */
    private List<PerOrg> scopeOrgs(boolean system) {
        QPerOrg q = QPerOrg.perOrg;
        if (systemScope(system)) {
            return queryFactory.selectFrom(q).orderBy(q.createTime.asc()).fetch();
        }
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return new ArrayList<>();
        }
        PerOrg org = orgManage.get(oid);
        return org == null ? new ArrayList<>() : new ArrayList<>(List.of(org));
    }

    private boolean canAdminOrg(Long oid) {
        Long uid = V3UserUtil.getUid();
        return V3UserUtil.isSysAdmin(uid) || (oid != null && oid.equals(V3UserUtil.getOid()) && permissionManage.isOrgAdmin(uid, oid));
    }

    private int rtId(String flag) {
        Integer rtId = EeResourceTypes.rtId(flag);
        if (rtId == null) {
            DEException.throwException("invalid flag: " + flag);
        }
        return rtId;
    }

    private static int normalizeWeight(int weight) {
        return weight == 5 || weight == 6 ? EeConstants.WEIGHT_EXPORT : weight;
    }

    // ------------------------------------------------------------------ subject tree

    public List<SubjectNode> subjectTree(SubjectRequest request) {
        List<PerOrg> orgs = scopeOrgs(request.isSystem());
        Set<Long> orgIds = orgs.stream().map(PerOrg::getId).collect(Collectors.toSet());
        Map<Long, SubjectNode> orgNodes = new LinkedHashMap<>();
        for (PerOrg org : orgs) {
            SubjectNode node = new SubjectNode(org.getId(), org.getPid(), org.getName(), 2);
            node.setAttrs(new HashMap<>());
            node.setChildren(new ArrayList<>());
            orgNodes.put(org.getId(), node);
        }
        if (request.getType() == EeConstants.SUBJECT_ROLE) {
            QPerRole r = QPerRole.perRole;
            List<PerRole> roles = orgIds.isEmpty() ? List.of() : queryFactory.selectFrom(r).where(r.oid.in(orgIds)).fetch();
            Map<Long, List<PerRole>> byOrg = roles.stream().collect(Collectors.groupingBy(PerRole::getOid));
            for (Map.Entry<Long, SubjectNode> entry : orgNodes.entrySet()) {
                List<PerRole> sorted = roleManage.rolesOfOrg(entry.getKey()).stream()
                        .filter(role -> byOrg.getOrDefault(entry.getKey(), List.of()).stream().anyMatch(item -> item.getId().equals(role.getId())))
                        .toList();
                for (PerRole role : sorted) {
                    SubjectNode node = new SubjectNode(role.getId(), role.getOid(), role.getName(), 1);
                    Map<String, Object> attrs = new HashMap<>();
                    attrs.put("root", Boolean.TRUE.equals(role.getRoot()));
                    attrs.put("typeCode", role.getTypeCode());
                    node.setAttrs(attrs);
                    entry.getValue().getChildren().add(node);
                }
            }
        } else {
            QPerUser u = QPerUser.perUser;
            List<PerUser> users = orgIds.isEmpty() ? List.of() : queryFactory.selectFrom(u)
                    .where(u.defaultOid.in(orgIds), u.id.ne(EeConstants.ADMIN_UID)).orderBy(u.createTime.desc()).fetch();
            for (PerUser user : users) {
                SubjectNode node = new SubjectNode(user.getId(), user.getDefaultOid(), user.getName(), 0);
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("account", user.getAccount());
                node.setAttrs(attrs);
                orgNodes.get(user.getDefaultOid()).getChildren().add(node);
            }
        }
        List<SubjectNode> roots = new ArrayList<>();
        for (PerOrg org : orgs) {
            SubjectNode node = orgNodes.get(org.getId());
            if (org.getPid() != null && orgNodes.containsKey(org.getPid())) {
                orgNodes.get(org.getPid()).getChildren().add(node);
            } else {
                node.setPid(0L);
                roots.add(node);
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ resource tree

    public List<ResourceVO> busiResource(BusiResourceRequest request) {
        String flag = StringUtils.lowerCase(request.getFlag());
        if (StringUtils.equals(flag, "menu")) {
            return menuTree();
        }
        int rtId = rtId(flag);
        boolean system = systemScope(request.isSystem());
        List<PerOrg> orgs = scopeOrgs(request.isSystem());
        ResourceVO root = new ResourceVO();
        root.setId(0L);
        root.setName("root");
        root.setLeaf(false);
        root.setChildren(new ArrayList<>());
        Long uid = V3UserUtil.getUid();
        for (PerOrg org : orgs) {
            List<ResourceVO> nodes = resourceNodes(rtId, org.getId(), uid);
            if (system) {
                ResourceVO orgNode = new ResourceVO();
                orgNode.setId(-org.getId());
                orgNode.setName(org.getName());
                orgNode.setLeaf(false);
                orgNode.setOid(org.getId());
                orgNode.setDisabled(true);
                orgNode.setExtraFlag(rtId == EeConstants.RT_DATASOURCE ? DatasourceConfiguration.DatasourceType.folder.getFlag() : 0);
                orgNode.setChildren(nodes);
                root.getChildren().add(orgNode);
            } else {
                root.getChildren().addAll(nodes);
            }
        }
        return new ArrayList<>(List.of(root));
    }

    private List<ResourceVO> resourceNodes(int rtId, Long oid, Long uid) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        List<PerBusiResource> resources = queryFactory.selectFrom(q).where(q.rtId.eq(rtId), q.orgId.eq(oid))
                .orderBy(q.createTime.desc()).fetch();
        Map<Long, Integer> dsFlags = rtId == EeConstants.RT_DATASOURCE ? datasourceFlags(resources) : Collections.emptyMap();
        boolean admin = canAdminOrg(oid);
        Map<Long, Perm> perms = admin ? Collections.emptyMap() : permissionManage.effective(uid, oid, rtId);
        Map<Long, ResourceVO> map = new LinkedHashMap<>();
        for (PerBusiResource resource : resources) {
            ResourceVO vo = new ResourceVO();
            vo.setId(resource.getId());
            vo.setName(resource.getName());
            vo.setLeaf(Boolean.TRUE.equals(resource.getLeaf()));
            vo.setOid(oid);
            vo.setExtraFlag(rtId == EeConstants.RT_DATASOURCE ? dsFlags.getOrDefault(resource.getId(), 27) : resource.getExtraFlag());
            vo.setDisabled(!admin && !perms.getOrDefault(resource.getId(), Perm.NONE).satisfies(EeConstants.WEIGHT_AUTH));
            vo.setChildren(new ArrayList<>());
            map.put(resource.getId(), vo);
        }
        List<ResourceVO> roots = new ArrayList<>();
        for (PerBusiResource resource : resources) {
            ResourceVO vo = map.get(resource.getId());
            if (resource.getPid() != null && map.containsKey(resource.getPid())) {
                map.get(resource.getPid()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        if (admin) {
            return roots;
        }
        // 非管理员只保留可授权节点及其祖先
        List<ResourceVO> result = new ArrayList<>();
        for (ResourceVO node : roots) {
            if (prune(node)) {
                result.add(node);
            }
        }
        return result;
    }

    private boolean prune(ResourceVO node) {
        List<ResourceVO> kids = new ArrayList<>();
        if (node.getChildren() != null) {
            for (ResourceVO child : node.getChildren()) {
                if (prune(child)) {
                    kids.add(child);
                }
            }
        }
        node.setChildren(kids);
        return !node.isDisabled() || !kids.isEmpty();
    }

    private Map<Long, Integer> datasourceFlags(List<PerBusiResource> resources) {
        if (resources.isEmpty()) {
            return Collections.emptyMap();
        }
        QCoreDatasource q = QCoreDatasource.coreDatasource;
        List<CoreDatasource> list = queryFactory.selectFrom(q).where(q.id.in(resources.stream().map(PerBusiResource::getId).toList())).fetch();
        Map<Long, Integer> result = new HashMap<>();
        for (CoreDatasource ds : list) {
            Integer flag = null;
            for (DatasourceConfiguration.DatasourceType type : DatasourceConfiguration.DatasourceType.values()) {
                if (type.getType().equals(ds.getType())) {
                    flag = type.getFlag();
                }
            }
            if (StringUtils.equalsIgnoreCase(ds.getType(), "dm")) {
                flag = 23;
            }
            result.put(ds.getId(), flag == null ? 27 : flag);
        }
        return result;
    }

    private List<ResourceVO> menuTree() {
        List<CoreMenu> menus = menuManage.allMenus();
        Set<Long> excluded = menuManage.subtree(EeMenuRegistry.SYS_ADMIN_ONLY, menus);
        Map<Long, CoreMenu> byId = new HashMap<>();
        menus.forEach(menu -> byId.put(menu.getId(), menu));
        Set<Long> keep = new HashSet<>();
        for (CoreMenu menu : menus) {
            if (excluded.contains(menu.getId()) || !Boolean.TRUE.equals(menu.getAuth()) || Objects.equals(menu.getType(), 1)) {
                continue;
            }
            CoreMenu current = menu;
            Set<Long> visited = new HashSet<>();
            while (current != null && visited.add(current.getId())) {
                keep.add(current.getId());
                current = current.getPid() == null || current.getPid() == 0L ? null : byId.get(current.getPid());
            }
        }
        List<CoreMenu> sorted = menus.stream().filter(menu -> keep.contains(menu.getId()))
                .sorted(Comparator.comparing(menu -> menu.getMenuSort() == null ? 0 : menu.getMenuSort())).toList();
        Map<Long, ResourceVO> map = new LinkedHashMap<>();
        for (CoreMenu menu : sorted) {
            ResourceVO vo = new ResourceVO();
            vo.setId(menu.getId());
            vo.setName(Translator.get("i18n_menu." + menu.getName()));
            vo.setLeaf(!Objects.equals(menu.getType(), 1));
            vo.setChildren(new ArrayList<>());
            map.put(menu.getId(), vo);
        }
        List<ResourceVO> roots = new ArrayList<>();
        for (CoreMenu menu : sorted) {
            ResourceVO vo = map.get(menu.getId());
            if (menu.getPid() != null && map.containsKey(menu.getPid())) {
                map.get(menu.getPid()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ subject dimension

    public PermissionVO subjectPermission(BasePermissionRequest request) {
        String flag = StringUtils.lowerCase(request.getFlag());
        int rtId = rtId(flag);
        boolean system = systemScope(request.isSystem());
        PermissionVO vo = new PermissionVO();
        vo.setPermissionOrigins(new ArrayList<>());
        int subjectType = request.getType() == null ? EeConstants.SUBJECT_USER : request.getType();
        Long subjectId = request.getId();
        Long subjectOid = subjectOid(subjectType, subjectId);
        if (!system && !Objects.equals(subjectOid, V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        vo.setPermissions(items(permissionManage.grants(subjectType, List.of(subjectId), rtId), subjectOid));
        String topRootDirId = system ? String.valueOf(-subjectOid) : "0";
        if (subjectType == EeConstants.SUBJECT_ROLE) {
            PerRole role = roleManage.get(subjectId);
            if (role != null && Boolean.TRUE.equals(role.getRoot())) {
                vo.getPermissionOrigins().add(origin(role, new ArrayList<>(), topRootDirId));
            }
        } else if (subjectOid != null) {
            for (PerRole role : permissionManage.roles(subjectId, subjectOid)) {
                List<PermissionItem> rolePerms = Boolean.TRUE.equals(role.getRoot()) ? new ArrayList<>()
                        : items(permissionManage.grants(EeConstants.SUBJECT_ROLE, List.of(role.getId()), rtId), subjectOid);
                vo.getPermissionOrigins().add(origin(role, rolePerms, topRootDirId));
            }
        }
        return vo;
    }

    private PermissionOrigin origin(PerRole role, List<PermissionItem> permissions, String topRootDirId) {
        PermissionOrigin origin = new PermissionOrigin(role.getId(), role.getName(), EeConstants.SUBJECT_ROLE, permissions);
        boolean root = Boolean.TRUE.equals(role.getRoot());
        origin.setOriginFlag(new PermissionOriginFlag(root, role.getTypeCode(), root ? Long.valueOf(topRootDirId) : null));
        return origin;
    }

    private static PermissionItem pi(Long id, int weight, int ext, Long oid) {
        PermissionItem item = new PermissionItem();
        item.setId(id);
        item.setWeight(weight);
        item.setExt(ext);
        item.setOid(oid);
        return item;
    }

    private List<PermissionItem> items(Map<Long, Perm> grants, Long oid) {
        List<PermissionItem> result = new ArrayList<>();
        grants.forEach((id, perm) -> {
            PermissionItem item = new PermissionItem();
            item.setId(id);
            item.setWeight(perm.weight());
            item.setExt(perm.ext());
            item.setOid(oid);
            result.add(item);
        });
        return result;
    }

    private Long subjectOid(int subjectType, Long subjectId) {
        if (subjectType == EeConstants.SUBJECT_ROLE) {
            PerRole role = roleManage.get(subjectId);
            return role == null ? null : role.getOid();
        }
        PerUser user = queryFactory.selectFrom(QPerUser.perUser).where(QPerUser.perUser.id.eq(subjectId)).fetchFirst();
        return user == null ? null : user.getDefaultOid();
    }

    @Transactional
    public void saveSubjectPermission(SubjectPermissionEditor editor) {
        String flag = StringUtils.lowerCase(editor.getFlag());
        int rtId = rtId(flag);
        int subjectType = editor.getType() == null ? EeConstants.SUBJECT_USER : editor.getType();
        Long subjectId = editor.getId();
        Long subjectOid = subjectOid(subjectType, subjectId);
        checkSubjectEditable(subjectType, subjectId, subjectOid);
        if (CollectionUtils.isEmpty(editor.getPermissions())) {
            return;
        }
        for (PermissionItem item : editor.getPermissions()) {
            saveOne(subjectType, subjectId, subjectOid, rtId, item.getId(), item.getWeight(), item.getExt());
        }
    }

    private void checkSubjectEditable(int subjectType, Long subjectId, Long subjectOid) {
        if (subjectType == EeConstants.SUBJECT_ROLE) {
            PerRole role = roleManage.get(subjectId);
            if (role == null) {
                DEException.throwException(Translator.get("ee_no_permission"));
            }
            if (Boolean.TRUE.equals(role.getRoot())) {
                DEException.throwException(Translator.get("ee_role_root_readonly"));
            }
        } else if (EeConstants.ADMIN_UID.equals(subjectId)) {
            DEException.throwException(Translator.get("ee_cannot_operate_admin"));
        }
        if (!V3UserUtil.isSysAdmin() && !Objects.equals(subjectOid, V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    private void saveOne(int subjectType, Long subjectId, Long subjectOid, int rtId, Long resourceId, int weight, int ext) {
        if (resourceId == null) {
            return;
        }
        int normalized = normalizeWeight(weight);
        if (rtId == EeConstants.RT_MENU) {
            if (!canAdminOrg(subjectOid) && !(subjectOid == null && V3UserUtil.isSysAdmin())) {
                DEException.throwException(Translator.get("ee_no_permission"));
            }
            grantManage.save(subjectType, subjectId, rtId, resourceId, Math.min(normalized, EeConstants.WEIGHT_READ), 0, subjectOid);
            return;
        }
        if (resourceId <= 0L) {
            if (resourceId < 0L) {
                // 组织占位节点不能授权
                return;
            }
            if (!canAdminOrg(subjectOid)) {
                DEException.throwException(Translator.get("ee_no_permission"));
            }
            grantManage.save(subjectType, subjectId, rtId, 0L, normalized >= EeConstants.WEIGHT_MANAGE ? normalized : 0, ext, subjectOid);
            return;
        }
        Long resourceOid = queryFactory.select(QPerBusiResource.perBusiResource.orgId).from(QPerBusiResource.perBusiResource)
                .where(QPerBusiResource.perBusiResource.id.eq(resourceId)).fetchFirst();
        if (resourceOid == null || !Objects.equals(resourceOid, subjectOid)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (!canAdminOrg(resourceOid)
                && !permissionManage.effective(V3UserUtil.getUid(), resourceOid, rtId, resourceId).satisfies(EeConstants.WEIGHT_AUTH)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        grantManage.save(subjectType, subjectId, rtId, resourceId, normalized, normalized <= 0 ? 0 : ext, resourceOid);
    }

    // ------------------------------------------------------------------ resource dimension

    public PermissionVO resourcePermission(BasePermissionRequest request) {
        String flag = StringUtils.lowerCase(request.getFlag());
        int rtId = rtId(flag);
        Long resourceId = request.getId();
        int subjectType = request.getType() == null ? EeConstants.SUBJECT_USER : request.getType();
        PermissionVO vo = new PermissionVO();
        vo.setPermissions(new ArrayList<>());
        vo.setPermissionOrigins(new ArrayList<>());
        Long oid;
        if (rtId == EeConstants.RT_MENU || resourceId == null || resourceId <= 0L) {
            oid = resourceId != null && resourceId < 0L ? -resourceId : (request.getOid() != null ? request.getOid() : V3UserUtil.getOid());
        } else {
            oid = queryFactory.select(QPerBusiResource.perBusiResource.orgId).from(QPerBusiResource.perBusiResource)
                    .where(QPerBusiResource.perBusiResource.id.eq(resourceId)).fetchFirst();
        }
        boolean system = systemScope(request.isSystem());
        if (oid == null || (!system && !Objects.equals(oid, V3UserUtil.getOid()))) {
            return vo;
        }
        List<Long> orgIds = system && rtId == EeConstants.RT_MENU && subjectType == EeConstants.SUBJECT_USER
                ? scopeOrgs(true).stream().map(PerOrg::getId).toList() : List.of(oid);
        List<PerRole> roles = roleManage.rolesOfOrgs(orgIds);
        Long rid = resourceId == null || resourceId < 0L ? 0L : resourceId;
        if (subjectType == EeConstants.SUBJECT_ROLE) {
            for (PerRole role : roles) {
                Perm perm = permissionManage.grants(EeConstants.SUBJECT_ROLE, List.of(role.getId()), rtId).get(rid);
                if (perm != null && !perm.isNone()) {
                    vo.getPermissions().add(pi(role.getId(), perm.weight(), perm.ext(), role.getOid()));
                }
                if (Boolean.TRUE.equals(role.getRoot())) {
                    Perm implicit = implicitPerm(role, rtId, rid);
                    List<PermissionItem> items = new ArrayList<>();
                    if (!implicit.isNone()) {
                        items.add(pi(role.getId(), implicit.weight(), implicit.ext(), role.getOid()));
                    }
                    vo.getPermissionOrigins().add(origin(role, items, "0"));
                }
            }
            return vo;
        }
        QPerUser u = QPerUser.perUser;
        List<PerUser> users = queryFactory.selectFrom(u).where(u.defaultOid.in(orgIds), u.id.ne(EeConstants.ADMIN_UID)).fetch();
        Map<Long, Perm> userGrants = new HashMap<>();
        if (!users.isEmpty()) {
            QPerAuth a = QPerAuth.perAuth;
            for (PerAuth auth : queryFactory.selectFrom(a).where(a.subjectType.eq(EeConstants.SUBJECT_USER),
                    a.subjectId.in(users.stream().map(PerUser::getId).toList()), a.rtId.eq(rtId), a.resourceId.eq(rid)).fetch()) {
                userGrants.merge(auth.getSubjectId(), new Perm(auth.getWeight(), auth.getExt()), Perm::merge);
            }
        }
        userGrants.forEach((uid, perm) -> {
            Long userOid = users.stream().filter(user -> user.getId().equals(uid)).map(PerUser::getDefaultOid).findFirst().orElse(oid);
            vo.getPermissions().add(pi(uid, perm.weight(), perm.ext(), userOid));
        });
        for (PerRole role : roles) {
            Perm perm = Boolean.TRUE.equals(role.getRoot()) ? implicitPerm(role, rtId, rid)
                    : permissionManage.grants(EeConstants.SUBJECT_ROLE, List.of(role.getId()), rtId).getOrDefault(rid, Perm.NONE);
            if (perm.isNone()) {
                continue;
            }
            List<PermissionItem> items = new ArrayList<>();
            for (Long uid : roleManage.userIdsOfRole(role.getId())) {
                if (!EeConstants.ADMIN_UID.equals(uid)) {
                    items.add(pi(uid, perm.weight(), perm.ext(), role.getOid()));
                }
            }
            if (!items.isEmpty()) {
                PermissionOrigin origin = new PermissionOrigin(role.getId(), role.getName(), EeConstants.SUBJECT_ROLE, items);
                origin.setOriginFlag(new PermissionOriginFlag(Boolean.TRUE.equals(role.getRoot()), role.getTypeCode(), null));
                vo.getPermissionOrigins().add(origin);
            }
        }
        return vo;
    }

    /**
     * 内置角色对资源的隐式权限
     */
    private Perm implicitPerm(PerRole role, int rtId, Long resourceId) {
        int typeCode = role.getTypeCode() == null ? 0 : role.getTypeCode();
        if (rtId == EeConstants.RT_MENU) {
            List<CoreMenu> menus = menuManage.allMenus();
            Set<Long> sysOnly = menuManage.subtree(EeMenuRegistry.SYS_ADMIN_ONLY, menus);
            Set<Long> orgCenter = menuManage.subtree(Set.of(EeMenuRegistry.SYSTEM), menus);
            if (sysOnly.contains(resourceId) || (orgCenter.contains(resourceId) && typeCode < EeConstants.ROLE_TYPE_ADMIN)
                    || (typeCode == EeConstants.ROLE_TYPE_STAFF && EeMenuRegistry.STAFF_RESTRICTED.contains(resourceId))) {
                return Perm.NONE;
            }
            return new Perm(EeConstants.WEIGHT_READ, 0);
        }
        if (resourceId == 0L) {
            return typeCode >= EeConstants.ROLE_TYPE_ANALYST ? new Perm(typeCode >= EeConstants.ROLE_TYPE_ADMIN ? EeConstants.WEIGHT_AUTH : EeConstants.WEIGHT_MANAGE, 0) : Perm.NONE;
        }
        return EePermissionManage.builtinPerm(typeCode, rtId);
    }

    @Transactional
    public void saveResourcePermission(ResourcePermissionEditor editor) {
        String flag = StringUtils.lowerCase(editor.getFlag());
        int rtId = rtId(flag);
        int subjectType = editor.getType() == null ? EeConstants.SUBJECT_USER : editor.getType();
        if (CollectionUtils.isEmpty(editor.getPermissions()) || CollectionUtils.isEmpty(editor.getResourceList())) {
            return;
        }
        for (BasePermissionItem item : editor.getPermissions()) {
            Long subjectId = item.getId();
            Long subjectOid = subjectOid(subjectType, subjectId);
            checkSubjectEditable(subjectType, subjectId, subjectOid);
            for (ResourceItemDTO resource : editor.getResourceList()) {
                saveOne(subjectType, subjectId, subjectOid, rtId, resource.getId(), item.getWeight(), item.getExt());
            }
        }
    }
}
