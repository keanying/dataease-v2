package io.dataease.ee.permissions.menu;

import io.dataease.ee.interact.EeExtension;
import io.dataease.menu.dao.auto.entity.CoreMenu;
import io.dataease.permission.util.V3UserUtil;

import java.util.List;
import java.util.Set;

/**
 * 菜单查询前：合并企业版菜单并按菜单权限裁剪
 */
@EeExtension("menuApi")
public class MenuApiExtension {

    private final EeMenuManage menuManage;

    public MenuApiExtension(EeMenuManage menuManage) {
        this.menuManage = menuManage;
    }

    public void query(List<CoreMenu> menus) {
        menuManage.merge(menus);
        Set<Long> visible = menuManage.visibleMenuIds(V3UserUtil.getUid(), V3UserUtil.getOid(), menus);
        menus.removeIf(menu -> !visible.contains(menu.getId()));
    }
}
