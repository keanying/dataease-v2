package io.dataease.ee.sync.provider;

import io.dataease.extensions.sync.model.task.Source;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 同步执行引擎使用的数据库方言：内置 JDBC 提供者直接实现，插件提供者通过适配器实现。
 */
public interface SyncDialect {

    /**
     * 解析（已解密的）连接配置 JSON，补全 jdbc 与驱动
     */
    DatasourceConfiguration configuration(String configurationJson);

    /**
     * 打开连接，调用方负责关闭
     */
    Connection open(DatasourceConfiguration configuration);

    String quote(String identifier);

    String qualified(DatasourceConfiguration configuration, String table);

    /**
     * 源端查询语句；incrementColumn 不为空时追加 {@code > ?} 增量条件（单个参数）
     */
    String selectSql(DatasourceConfiguration configuration, Source source, List<String> columns, String incrementColumn);

    default int fetchSize() {
        return SyncProviderDefaults.FETCH_SIZE;
    }

    boolean tableExists(Connection connection, DatasourceConfiguration configuration, String table) throws SQLException;

    void dropTable(Connection connection, DatasourceConfiguration configuration, String table) throws SQLException;

    /**
     * 建表语句（可能为多条）
     */
    List<String> createTableStatements(Connection connection, DatasourceConfiguration configuration, String table, Target target) throws SQLException;

    /**
     * 用临时表替换目标表（目标表可能不存在）
     */
    void replaceTable(Connection connection, DatasourceConfiguration configuration, String tempTable, String table) throws SQLException;

    String tempTableName(String table);

    String insertSql(DatasourceConfiguration configuration, String table, List<String> columns);

    /**
     * 按主键写入（存在则更新）的语句；返回 null 表示由引擎按“先删除再插入”处理
     */
    String upsertSql(Connection connection, DatasourceConfiguration configuration, String table, List<String> columns, List<String> keys) throws SQLException;

    /**
     * 目标表增量字段当前最大值
     */
    Object maxValue(Connection connection, DatasourceConfiguration configuration, String table, String column) throws SQLException;

    int nullType();

    Object readValue(ResultSet resultSet, int index) throws SQLException;
}
