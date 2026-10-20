package io.dataease.ee.init;

import io.dataease.initSql.SqlBlock;

/**
 * 企业版数据初始化与升级，版本组 3（版本号以 3 开头）
 */
public interface EeSqlBlock extends SqlBlock {

    @Override
    default String getVersionGroup() {
        return "3";
    }
}
