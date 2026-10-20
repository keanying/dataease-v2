package io.dataease.ee.permissions.resource;

/**
 * 按资源类型删除业务资源（含下级），用于删除组织时同步删除资源。
 */
public interface EeResourceDeleter {

    int rtId();

    void delete(Long id);
}
