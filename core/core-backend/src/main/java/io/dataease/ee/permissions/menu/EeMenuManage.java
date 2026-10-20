package io.dataease.ee.permissions.menu;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.menu.dao.auto.entity.CoreMenu;
import io.dataease.menu.manage.MenuManage;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单权限：
 * <ul>
 *     <li>系统管理员可见全部菜单；“系统设置”仅系统管理员可见；</li>
 *     <li>不参与授权的菜单对组织内用户可见；</li>
 *     <li>组织内置角色隐式拥有菜单：组织管理中心仅组织管理员，普通用户不含 {@link EeMenuRegistry#STAFF_RESTRICTED}；</li>
 *     <li>另加被授予（用户或角色）的菜单；目录在有可见子菜单时可见。</li>
 * </ul>
 */
@Component
public class EeMenuManage {

    private final MenuManage menuManage;

    private final EePermissionManage permissionManage;

    public EeMenuManage(MenuManage menuManage, EePermissionManage permissionManage) {
        this.menuManage = menuManage;
        this.permissionManage = permissionManage;
    }

    /**
     * 核心菜单与企业版菜单合并后的全部菜单
     */
    public List<CoreMenu> allMenus() {
        List<CoreMenu> menus = new ArrayList<>(menuManage.coreMenus());
        merge(menus);
        return menus;
    }

    /**
     * 把企业版菜单合并进列表（同 ID 以核心菜单为准）
     */
    public void merge(List<CoreMenu> menus) {
        Set<Long> ids = menus.stream().map(CoreMenu::getId).collect(Collectors.toSet());
        for (CoreMenu menu : EeMenuRegistry.menus()) {
            if (!ids.contains(menu.getId())) {
                menus.add(menu);
            }
        }
    }

    /**
     * 当前身份可见的菜单ID
     */
    public Set<Long> visibleMenuIds(Long uid, Long oid, List<CoreMenu> menus) {
        Map<Long, CoreMenu> byId = new HashMap<>();
        menus.forEach(menu -> byId.put(menu.getId(), menu));
        if (uid == null) {
            return new HashSet<>();
        }
        if (permissionManage.isSysAdmin(uid)) {
            return new HashSet<>(byId.keySet());
        }
        Set<Long> sysOnly = subtree(EeMenuRegistry.SYS_ADMIN_ONLY, menus);
        Set<Long> orgCenter = subtree(Set.of(EeMenuRegistry.SYSTEM), menus);
        int builtin = oid == null ? -1 : permissionManage.builtinRoleType(uid, oid);
        Set<Long> granted = grantedMenuIds(uid, oid);
        Set<Long> visibleLeaves = new HashSet<>();
        for (CoreMenu menu : menus) {
            Long id = menu.getId();
            if (sysOnly.contains(id) || Objects.equals(menu.getType(), 1)) {
                continue;
            }
            if (oid == null && !EeMenuRegistry.PERSONAL.contains(id)) {
                continue;
            }
            boolean visible = !Boolean.TRUE.equals(menu.getAuth()) || granted.contains(id);
            if (!visible && builtin >= 0) {
                visible = (!orgCenter.contains(id) || builtin >= EeConstants.ROLE_TYPE_ADMIN)
                        && !(builtin == EeConstants.ROLE_TYPE_STAFF && EeMenuRegistry.STAFF_RESTRICTED.contains(id));
            }
            if (visible) {
                visibleLeaves.add(id);
            }
        }
        Set<Long> result = new HashSet<>(visibleLeaves);
        for (Long id : visibleLeaves) {
            CoreMenu current = byId.get(id);
            Set<Long> visited = new HashSet<>();
            while (current != null && current.getPid() != null && current.getPid() != 0L && visited.add(current.getId())) {
                result.add(current.getPid());
                current = byId.get(current.getPid());
            }
        }
        return result;
    }

    /**
     * 组织管理中心是否可见（内置组织管理员或被授予组织管理中心菜单）
     */
    public boolean orgCenterVisible() {
        return currentVisibleMenuIds().contains(EeMenuRegistry.SYSTEM);
    }

    public Set<Long> currentVisibleMenuIds() {
        return visibleMenuIds(V3UserUtil.getUid(), V3UserUtil.getOid(), allMenus());
    }

    public boolean canAccess(Long menuId) {
        return currentVisibleMenuIds().contains(menuId);
    }

    private Set<Long> grantedMenuIds(Long uid, Long oid) {
        Set<Long> result = new HashSet<>();
        Map<Long, Perm> userGrants = permissionManage.grants(EeConstants.SUBJECT_USER, List.of(uid), EeConstants.RT_MENU);
        userGrants.forEach((id, perm) -> {
            if (!perm.isNone()) {
                result.add(id);
            }
        });
        if (oid != null) {
            Map<Long, Perm> roleGrants = permissionManage.grants(EeConstants.SUBJECT_ROLE, permissionManage.roleIds(uid, oid), EeConstants.RT_MENU);
            roleGrants.forEach((id, perm) -> {
                if (!perm.isNone()) {
                    result.add(id);
                }
            });
        }
        return result;
    }

    public Set<Long> subtree(Set<Long> roots, List<CoreMenu> menus) {
        Set<Long> result = new HashSet<>(roots);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CoreMenu menu : menus) {
                if (!result.contains(menu.getId()) && result.contains(menu.getPid())) {
                    result.add(menu.getId());
                    changed = true;
                }
            }
        }
        return result;
    }
}
