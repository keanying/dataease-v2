package io.dataease.ee.datafilling.jdbc;

import io.dataease.dao.auto.entity.CoreDatasource;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.ee.datafilling.DfConstants;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.factory.ExtDDLProviderFactory;
import io.dataease.extensions.datafilling.provider.ExtDDLProvider;
import io.dataease.extensions.datasource.dto.ConnectionObj;
import io.dataease.extensions.datasource.dto.DatasourceDTO;
import io.dataease.extensions.datasource.factory.ProviderFactory;
import io.dataease.extensions.datasource.provider.Provider;
import io.dataease.i18n.Translator;
import io.dataease.utils.BeanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 打开填报存储/选项数据源的 JDBC 会话，选择 DDL 生成器
 */
@Component
public class DfJdbcSupport {

    private static final List<String> ENGINE_STORED_TYPES = List.of("Excel", "ExcelRemote", "API");

    private final DataSourceManage dataSourceManage;

    private final ExtDDLProvider mysqlProvider;

    private final ExtDDLProvider pgProvider;

    public DfJdbcSupport(@Lazy DataSourceManage dataSourceManage,
                         @Lazy @org.springframework.beans.factory.annotation.Qualifier("mysqlExtDDLProvider") ExtDDLProvider mysqlProvider,
                         @Lazy @org.springframework.beans.factory.annotation.Qualifier("pgExtDDLProvider") ExtDDLProvider pgProvider) {
        this.dataSourceManage = dataSourceManage;
        this.mysqlProvider = mysqlProvider;
        this.pgProvider = pgProvider;
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
        session.setDdl(ddlProvider(ds.getType()));
        return session;
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
            return mysqlProvider;
        }
        ExtDDLProvider plugin = ExtDDLProviderFactory.getInstance(type);
        if (plugin != null) {
            return plugin;
        }
        if (StringUtils.equalsAnyIgnoreCase(type, "pg", "kingbase")) {
            return pgProvider;
        }
        return null;
    }

    public ExtDDLProvider requireDdl(DfSession session) {
        if (session.ddl() == null) {
            DEException.throwException(Translator.get("ee_df_datasource_not_support"));
        }
        return session.ddl();
    }
}
