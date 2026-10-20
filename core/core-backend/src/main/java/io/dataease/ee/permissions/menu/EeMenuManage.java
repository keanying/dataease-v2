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
 *     <li>系统管理员可见全部菜单；</li>
 *     <li>组织管理员可见除“系统设置”外的全部菜单；</li>
 *     <li>其他用户可见不参与授权的菜单，以及被授予（用户或角色）的菜单；目录在有可见子菜单时可见。</li>
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
        if (permissionManage.isSysAdmin(uid)) {
            return new HashSet<>(byId.keySet());
        }
        Set<Long> sysOnly = subtree(EeMenuRegistry.SYS_ADMIN_ONLY, menus);
        boolean orgAdmin = oid != null && permissionManage.isOrgAdmin(uid, oid);
        Set<Long> granted = grantedMenuIds(uid, oid);
        Set<Long> visibleLeaves = new HashSet<>();
        for (CoreMenu menu : menus) {
            if (sysOnly.contains(menu.getId()) || Objects.equals(menu.getType(), 1)) {
                continue;
            }
            if (oid == null && isOrgScoped(menu, byId)) {
                continue;
            }
            if (orgAdmin || !Boolean.TRUE.equals(menu.getAuth()) || granted.contains(menu.getId())) {
                visibleLeaves.add(menu.getId());
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

    /**
     * 没有组织的用户只能看到工作台等个人菜单
     */
    private boolean isOrgScoped(CoreMenu menu, Map<Long, CoreMenu> byId) {
        return !Set.of(1L, 80L, 81L).contains(menu.getId());
    }

    private Set<Long> subtree(Set<Long> roots, List<CoreMenu> menus) {
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
