package io.dataease.ee.datafilling.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import io.dataease.dao.auto.entity.CoreDatasource;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.ee.datafilling.DfConstants;
import io.dataease.ee.datafilling.ddl.SqlServerExtDDLProvider;
import io.dataease.ee.datafilling.manage.DfJson;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.factory.ExtDDLProviderFactory;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datafilling.vo.XpackPluginsDfVO;
import io.dataease.extensions.datasource.dto.ConnectionObj;
import io.dataease.extensions.datasource.dto.DatasourceDTO;
import io.dataease.extensions.datasource.factory.ProviderFactory;
import io.dataease.extensions.datasource.provider.Provider;
import io.dataease.i18n.Translator;
import io.dataease.utils.BeanUtils;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 打开填报存储/选项数据源的 JDBC 会话，选择 DDL 生成器。
 * <p>内置支持的存储类型：mysql、mariadb、pg、kingbase、sqlServer、oracle、dm（达梦，内置数据源 {@code io.dataease.ee.datasource.dm}）；
 * 其他类型需由数据填报插件（{@link ExtDDLProviderFactory}）提供。</p>
 */
@Component
public class DfJdbcSupport {

    private static final List<String> ENGINE_STORED_TYPES = List.of("Excel", "ExcelRemote", "API");

    private final DataSourceManage dataSourceManage;

    private final Map<String, ExtDDLProvider> builtin;

    private final SqlServerExtDDLProvider sqlServerProvider;

    public DfJdbcSupport(@Lazy DataSourceManage dataSourceManage,
                         @Qualifier("mysqlExtDDLProvider") ExtDDLProvider mysqlProvider,
                         @Qualifier("pgExtDDLProvider") ExtDDLProvider pgProvider,
                         @Qualifier("sqlServerExtDDLProvider") SqlServerExtDDLProvider sqlServerProvider,
                         @Qualifier("oracleExtDDLProvider") ExtDDLProvider oracleProvider,
                         @Qualifier("dmExtDDLProvider") ExtDDLProvider dmProvider) {
        this.dataSourceManage = dataSourceManage;
        this.sqlServerProvider = sqlServerProvider;
        this.builtin = Map.of(
                "mysql", mysqlProvider,
                "mariadb", mysqlProvider,
                "pg", pgProvider,
                "kingbase", pgProvider,
                "sqlserver", sqlServerProvider,
                "oracle", oracleProvider,
                "dm", dmProvider);
    }

    /**
     * 数据源（已解密配置），-1 为内置引擎
     */
    public CoreDatasource datasource(Long id) {
        if (id == null) {
            DEException.throwException(Translator.get("ee_df_datasource_not_exists"));
        }
        CoreDatasource ds = null;
        try {
            ds = dataSourceManage.getCoreDatasource(id);
        } catch (Exception ignore) {
        }
        if (ds == null || StringUtils.equalsIgnoreCase(ds.getType(), "folder")) {
            DEException.throwException(Translator.get("ee_df_datasource_not_exists"));
        }
        return ds;
    }

    public CoreDatasource engine() {
        return dataSourceManage.getCoreDatasource(DfConstants.ENGINE_DS_ID);
    }

    /**
     * 打开会话；Excel/API 类数据源的数据存放在内置引擎中
     */
    public DfSession open(Long dsId) {
        CoreDatasource ds = datasource(dsId);
        if (ENGINE_STORED_TYPES.contains(ds.getType())) {
            ds = engine();
        }
        return open(ds);
    }

    public DfSession open(CoreDatasource ds) {
        DatasourceDTO dto = new DatasourceDTO();
        BeanUtils.copyBean(dto, ds);
        ConnectionObj obj = null;
        try {
            Provider provider = ProviderFactory.getProvider(ds.getType());
            obj = provider.getConnection(dto);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_df_datasource_connect_failed") + ": " + e.getMessage());
        }
        if (obj == null || obj.getConnection() == null) {
            DEException.throwException(Translator.get("ee_df_datasource_connect_failed"));
        }
        DfSession session = new DfSession(obj, ds.getType());
        try {
            initSession(session, ds);
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    /**
     * 按数据源配置的模式初始化会话并选择 DDL 生成器
     */
    private void initSession(DfSession session, CoreDatasource ds) {
        ExtDDLProvider ddl = ddlProvider(ds.getType());
        String schema = configuredSchema(ds);
        DfDialect dialect = session.dialect();
        if (dialect.isSqlServer()) {
            if (StringUtils.isBlank(schema)) {
                List<List<String>> rows = session.queryStrings("SELECT SCHEMA_NAME()", null, 1);
                schema = rows.isEmpty() ? null : rows.getFirst().getFirst();
            }
            session.setSchema(StringUtils.defaultIfBlank(schema, "dbo"));
            if (ddl == sqlServerProvider) {
                ddl = sqlServerProvider.withSchema(session.getSchema());
            }
        } else if (dialect.isOracleLike() && StringUtils.isNotBlank(schema)) {
            String current = null;
            try {
                current = session.connection().getSchema();
            } catch (Throwable ignore) {
            }
            if (!StringUtils.equals(current, schema.trim())) {
                String sql = dialect.isOracle() ? "ALTER SESSION SET CURRENT_SCHEMA = " + session.q(schema.trim())
                        : "SET SCHEMA " + session.q(schema.trim());
                try {
                    session.executeOne(sql);
                } catch (Exception e) {
                    LogUtil.warn("data filling: switch schema failed: " + e.getMessage());
                }
            }
        }
        session.setDdl(ddl);
    }

    private String configuredSchema(CoreDatasource ds) {
        if (StringUtils.isBlank(ds.getConfiguration())) {
            return null;
        }
        try {
            JsonNode node = DfJson.MAPPER.readTree(ds.getConfiguration());
            JsonNode schema = node == null ? null : node.get("schema");
            return schema == null || schema.isNull() ? null : StringUtils.trimToNull(schema.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 是否有可用的 DDL 生成器
     */
    public boolean supportDdl(String type) {
        return ddlProvider(type) != null;
    }

    public ExtDDLProvider ddlProvider(String type) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        if (StringUtils.equalsAnyIgnoreCase(type, "mysql", "mariadb")) {
            return builtin.get("mysql");
        }
        ExtDDLProvider plugin = ExtDDLProviderFactory.getInstance(type);
        if (plugin != null) {
            return plugin;
        }
        return builtin.get(type.toLowerCase());
    }

    /**
     * 内置支持数据填报的数据源类型对应的插件描述（前端据此显示“开启数据填报”开关）；没有内置支持时返回 null
     */
    public XpackPluginsDfVO builtinPlugin(String type) {
        if (StringUtils.isBlank(type) || !builtin.containsKey(type.toLowerCase())) {
            return null;
        }
        XpackPluginsDfVO vo = new XpackPluginsDfVO();
        vo.setType(type);
        vo.setCategory("builtin");
        vo.setFlag(0);
        return vo;
    }

    public ExtDDLProvider requireDdl(DfSession session) {
        if (session.ddl() == null) {
            DEException.throwException(Translator.get("ee_df_datasource_not_support"));
        }
        return session.ddl();
    }
}
