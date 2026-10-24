package io.dataease.ee.sync.manage;

import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * 同步管理访问控制：系统管理员，或可见对应同步菜单（组织管理中心 → 同步管理）的用户；数据范围为当前组织
 */
@Component
public class SyncAccess {

    private final EeMenuManage menuManage;

    public SyncAccess(EeMenuManage menuManage) {
        this.menuManage = menuManage;
    }

    public void check(Long... menuIds) {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (V3UserUtil.isSysAdmin(uid)) {
            return;
        }
        Set<Long> visible = menuManage.currentVisibleMenuIds();
        if (Arrays.stream(menuIds).noneMatch(visible::contains)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    public Long oid() {
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return oid;
    }
}
