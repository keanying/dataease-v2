package io.dataease.ee.permissions.user;

import io.dataease.ee.interact.EeExtension;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;

/**
 * 用户名称解析
 */
@EeExtension("coreUserManage")
public class CoreUserExtension {

    public String getUserName(Long uid) {
        V3BaseUser user = V3UserUtil.getUser(uid);
        return user == null ? null : user.getName();
    }
}
