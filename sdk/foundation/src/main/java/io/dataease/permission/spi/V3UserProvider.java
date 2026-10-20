package io.dataease.permission.spi;

import io.dataease.permission.model.V3BaseUser;

/**
 * 用户信息提供者，由 core-backend 以 Spring Bean 形式实现并在启动时注册到 {@link io.dataease.permission.util.V3UserUtil}。
 */
public interface V3UserProvider {

    /**
     * 按 ID 查询用户，不存在时返回 {@code null}
     */
    V3BaseUser getUser(Long uid);

    /**
     * 是否系统管理员
     */
    boolean isSysAdmin(Long uid);
}
