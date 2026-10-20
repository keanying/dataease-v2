package io.dataease.ee.permissions.menu;

import io.dataease.menu.dao.auto.entity.CoreMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 企业版菜单定义。菜单不落库，由 menuApi 扩展在查询时合并到核心菜单中。
 *
 * <p>组件路径对应 {@code core-frontend/src/views/pages} 下的页面（plugin 菜单）。</p>
 */
public final class EeMenuRegistry {

    private EeMenuRegistry() {
    }

    /**
     * 组织管理中心
     */
    public static final long SYSTEM = 7L;

    /**
     * 系统设置
     */
    public static final long SYS_SETTING = 15L;

    public static final long SYS_USER = 111L;

    /**
     * 仅系统管理员可见的菜单子树根
     */
    public static final Set<Long> SYS_ADMIN_ONLY = Set.of(SYS_SETTING);

    /**
     * 内置普通用户角色不可见的菜单（与前端权限配置页约定一致）
     */
    public static final Set<Long> STAFF_RESTRICTED = Set.of(13L, 62L, 63L);

    /**
     * 不属于任何组织的用户也可见的个人菜单
     */
    public static final Set<Long> PERSONAL = Set.of(1L, 80L, 81L);

    public static List<CoreMenu> menus() {
        List<CoreMenu> menus = new ArrayList<>();
        // 组织管理中心
        menus.add(menu(SYSTEM, 0L, 1, "system", null, 5, null, "/system", true, true, false));
        menus.add(menu(8L, SYSTEM, 2, "user", "/menu/system/member/index", 1, "peoples", "/user", false, true, true));
        menus.add(menu(10L, SYSTEM, 2, "auth", "/menu/system/auth/index", 2, "auth", "/auth", false, true, true));
        menus.add(menu(21L, SYSTEM, 1, "sync", null, 3, "sync", "/sync", false, true, false));
        menus.add(menu(22L, 21L, 2, "summary", "/menu/system/sync/summary/index", 1, null, "/summary", false, true, true));
        menus.add(menu(23L, 21L, 2, "ds", "/menu/system/sync/ds/index", 2, null, "/ds", false, true, true));
        menus.add(menu(24L, 21L, 2, "task", "/menu/system/sync/task/index", 3, null, "/task", false, true, true));
        menus.add(menu(13L, SYSTEM, 2, "report", "/menu/system/report/index", 4, "report", "/report", false, true, true));
        menus.add(menu(62L, SYSTEM, 2, "threshold", "/menu/system/threshold/index", 5, "threshold", "/threshold", false, true, true));
        menus.add(menu(63L, SYSTEM, 2, "webhook", "/menu/system/webhook/index", 6, "webhook", "/webhook", false, true, true));
        menus.add(menu(65L, SYSTEM, 2, "association", "/menu/system/association/index", 7, "association", "/association", false, true, true));

        // 系统设置
        menus.add(menu(SYS_USER, SYS_SETTING, 2, "sysuser", "/menu/system/sysuser/index", 1, "sysuser", "/sysuser", false, true, false));
        menus.add(menu(9L, SYS_SETTING, 2, "org", "/menu/system/org/index", 2, "org", "/org", false, true, false));
        menus.add(menu(14L, SYS_SETTING, 2, "sysauth", "/menu/system/auth/index", 3, "auth", "/auth", false, true, false));
        menus.add(menu(18L, SYS_SETTING, 2, "sysVariable", "/menu/setting/variable/index", 5, "variable", "/sysVariable", false, true, false));
        menus.add(menu(20L, SYS_SETTING, 2, "watermark", "/menu/system/watermark/index", 6, "watermark", "/watermark", false, true, false));
        menus.add(menu(26L, SYS_SETTING, 2, "appearance", "/menu/setting/appearance/index", 7, "appearance", "/appearance", false, true, false));
        menus.add(menu(27L, SYS_SETTING, 2, "authentication", "/menu/setting/authentication/index", 8, "authentication", "/authentication", false, true, false));
        menus.add(menu(28L, SYS_SETTING, 2, "platform", "/menu/setting/platform/index", 9, "platform", "/platform", false, true, false));
        menus.add(menu(25L, SYS_SETTING, 2, "embedded", "/menu/setting/embedded/index", 11, "embedded", "/embedded", false, true, false));
        menus.add(menu(29L, SYS_SETTING, 2, "plugin", "/menu/setting/plugin/index", 12, "plugin", "/plugin", false, true, false));
        menus.add(menu(33L, SYS_SETTING, 2, "security", "/menu/setting/security/index", 13, "security", "/security", false, true, false));

        // 工具箱
        menus.add(menu(32L, 30L, 2, "toolbox-log", "/menu/system/log/index", 30, "log", "/log", false, true, true));

        // 数据准备
        menus.add(menu(60L, 4L, 2, "data-filling-manage", "/menu/data/data-filling/manage/index", 3, null, "/data-filling-manage", false, true, true));
        menus.add(menu(61L, 0L, 2, "data-filling-form", "/menu/data/data-filling/manage/form/index", 7, null, "/df-form", true, false, false));

        // 电子表格
        menus.add(menu(100L, 0L, 2, "spreadsheet", "/menu/spreadsheet/index", 4, null, "/spreadsheet", false, true, true));
        menus.add(menu(101L, 0L, 2, "spreadsheet-editor", "/menu/spreadsheet/editor", 8, null, "/spreadsheet-editor", true, false, false));

        // 消息
        menus.add(menu(71L, 70L, 2, "msg-fill", "/menu/data/data-filling/fill/MsgFillTable", 1, null, "/msg-fill", false, true, false));

        // 个人中心、API 文档
        menus.add(menu(80L, 0L, 2, "user-center", "/menu/user-center/index", 100, null, "/user-center", true, true, false));
        menus.add(menu(81L, 0L, 2, "apidoc", "/menu/apidoc/index", 101, null, "/apidoc", true, true, false));
        return menus;
    }

    private static CoreMenu menu(Long id, Long pid, Integer type, String name, String component, Integer sort, String icon,
                                 String path, boolean hidden, boolean inLayout, boolean auth) {
        CoreMenu menu = new CoreMenu();
        menu.setId(id);
        menu.setPid(pid);
        menu.setType(type);
        menu.setName(name);
        menu.setComponent(component);
        menu.setMenuSort(sort);
        menu.setIcon(icon);
        menu.setPath(path);
        menu.setHidden(hidden);
        menu.setInLayout(inLayout);
        menu.setAuth(auth);
        menu.setPlugin(component != null);
        return menu;
    }
}
