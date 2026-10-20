package io.dataease.ee.permissions.user;

import io.dataease.api.permissions.variable.dto.SysVariableValueItem;

import java.util.Collections;
import java.util.List;

/**
 * 用户系统变量的读写扩展点，由系统变量模块实现。
 */
public interface EeUserVariableSupport {

    default List<SysVariableValueItem> userVariables(Long uid) {
        return Collections.emptyList();
    }

    default void saveUserVariables(Long uid, List<SysVariableValueItem> items) {
    }

    /**
     * 用户列表中展示的系统变量摘要
     */
    default String summary(Long uid) {
        return null;
    }

    default void deleteUser(Long uid) {
    }
}
