package io.dataease.ee.sync.provider;

import io.dataease.extensions.sync.provider.SyncProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内置同步数据源提供者。
 *
 * <p>Bean 名称与 SDK {@code DatasourceConfiguration.DatasourceType#getProviderClassName()} 一致，供
 * {@code SyncProviderFactory.getProvider} 通过 {@code getBean(providerClassName, SyncProvider.class)} 获取；
 * 其余内置类型（MySQL/MariaDB/PostgreSQL/SQL Server/Oracle 目标端等）由 {@link SyncProviderResolver} 解析。</p>
 */
@Configuration
public class SyncProviderBeans {

    @Bean("mySQLSourceProvider")
    public SyncProvider mySQLSourceProvider() {
        return new MysqlSyncProvider("mysql");
    }

    @Bean("dorisSinkProvider")
    public SyncProvider dorisSinkProvider() {
        return new DorisSyncProvider();
    }

    @Bean("oracleSourceProvider")
    public SyncProvider oracleSourceProvider() {
        return new OracleSyncProvider();
    }

    @Bean("sqlServerSourceProvider")
    public SyncProvider sqlServerSourceProvider() {
        return new SqlServerSyncProvider();
    }

    @Bean("db2SourceProvider")
    public SyncProvider db2SourceProvider() {
        return new Db2SyncProvider();
    }

    @Bean("elasticsearchSourceProvider")
    public SyncProvider elasticsearchSourceProvider() {
        return new EsSyncProvider();
    }

    @Bean("mySQLSinkProvider")
    public SyncProvider mySQLSinkProvider() {
        return new MysqlSyncProvider("mysql");
    }

    @Bean("mariadbSourceProvider")
    public SyncProvider mariadbSourceProvider() {
        return new MysqlSyncProvider("mariadb");
    }

    @Bean("mariadbSinkProvider")
    public SyncProvider mariadbSinkProvider() {
        return new MysqlSyncProvider("mariadb");
    }

    @Bean("postgresqlSourceProvider")
    public SyncProvider postgresqlSourceProvider() {
        return new PgSyncProvider();
    }

    @Bean("postgresqlSinkProvider")
    public SyncProvider postgresqlSinkProvider() {
        return new PgSyncProvider();
    }

    @Bean("sqlServerSinkProvider")
    public SyncProvider sqlServerSinkProvider() {
        return new SqlServerSyncProvider();
    }

    @Bean("oracleSinkProvider")
    public SyncProvider oracleSinkProvider() {
        return new OracleSyncProvider();
    }
}
