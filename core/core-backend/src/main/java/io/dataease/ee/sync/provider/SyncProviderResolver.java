package io.dataease.ee.sync.provider;

import io.dataease.exception.DEException;
import io.dataease.extensions.sync.api.SyncPluginManageApi;
import io.dataease.extensions.sync.factory.SyncProviderFactory;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.datasource.DatasourceRole;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.provider.SourceProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.extensions.sync.vo.DatasourceConfiguration;
import io.dataease.extensions.sync.vo.XpackPluginsSyncDatasourceVO;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 同步数据源提供者解析：SDK 内置枚举 → 已加载插件 → 本模块扩展的内置类型
 */
@Component
public class SyncProviderResolver {

    /**
     * SDK 枚举之外的内置类型：type-role → bean
     */
    private static final Map<String, String> EXTRA_BUILTIN = Map.of(
            "mysql-2", "mySQLSinkProvider",
            "mariadb-1", "mariadbSourceProvider",
            "mariadb-2", "mariadbSinkProvider",
            "postgresql-1", "postgresqlSourceProvider",
            "postgresql-2", "postgresqlSinkProvider",
            "sqlserver-2", "sqlServerSinkProvider",
            "oracle-2", "oracleSinkProvider");

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "mariadb", "MariaDB",
            "postgresql", "PostgreSQL");

    private final ApplicationContext context;
    private final ObjectProvider<SyncPluginManageApi> pluginCatalog;

    public SyncProviderResolver(ApplicationContext context, ObjectProvider<SyncPluginManageApi> pluginCatalog) {
        this.context = context;
        this.pluginCatalog = pluginCatalog;
    }

    public SyncProvider provider(String type, Integer role) {
        if (StringUtils.isBlank(type) || !DatasourceRole.isValid(role)) {
            DEException.throwException(Translator.get("ee_sync_ds_type_invalid", StringUtils.defaultString(type)));
        }
        String trimmed = type.trim();
        DatasourceConfiguration.DatasourceType builtin = builtin(trimmed, role);
        if (builtin != null) {
            return context.getBean(builtin.getProviderClassName(), SyncProvider.class);
        }
        SyncProvider plugin = SyncProviderFactory.getInstance(trimmed, role);
        if (plugin != null) {
            return plugin;
        }
        String bean = EXTRA_BUILTIN.get(trimmed.toLowerCase(Locale.ROOT) + "-" + role);
        if (bean != null) {
            return context.getBean(bean, SyncProvider.class);
        }
        DEException.throwException(Translator.get("ee_sync_ds_type_invalid", trimmed + "(" + DatasourceRole.displayName(role) + ")"));
        return null;
    }

    public SourceProvider source(SyncProvider provider) {
        if (provider instanceof SourceProvider source) {
            return source;
        }
        DEException.throwException(Translator.get("ee_sync_source_unsupported", provider.getClass().getSimpleName()));
        return null;
    }

    public SinkProvider sink(SyncProvider provider) {
        if (provider instanceof SinkProvider sink) {
            return sink;
        }
        DEException.throwException(Translator.get("ee_sync_sink_unsupported", provider.getClass().getSimpleName()));
        return null;
    }

    public SyncDialect dialect(String type, Integer role, DatasourceRequest request) {
        SyncProvider provider = provider(type, role);
        if (provider instanceof SyncDialect dialect) {
            return dialect;
        }
        if (provider instanceof UnsupportedSyncProvider) {
            provider.validate(request);
        }
        return new PluginSyncDialect(provider, plugin(type, role), request);
    }

    private static DatasourceConfiguration.DatasourceType builtin(String type, Integer role) {
        return Arrays.stream(DatasourceConfiguration.DatasourceType.values())
                .filter(t -> t.getDatasourceRole().equals(role) && t.getType().equalsIgnoreCase(type))
                .findFirst().orElse(null);
    }

    public XpackPluginsSyncDatasourceVO plugin(String type, Integer role) {
        try {
            SyncPluginManageApi api = pluginCatalog.getIfAvailable();
            List<XpackPluginsSyncDatasourceVO> list = api == null ? List.of() : api.queryPluginSyncDatasource();
            return list.stream().filter(p -> StringUtils.equalsIgnoreCase(p.getType(), type) && role.equals(p.getDatasourceRole()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            LogUtil.warn("query sync plugin failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 类型显示名
     */
    public String typeName(String type, Integer role) {
        if (StringUtils.isBlank(type)) {
            return type;
        }
        DatasourceConfiguration.DatasourceType builtin = Arrays.stream(DatasourceConfiguration.DatasourceType.values())
                .filter(t -> t.getType().equalsIgnoreCase(type)).findFirst().orElse(null);
        if (builtin != null) {
            return builtin.getName();
        }
        if (role != null) {
            XpackPluginsSyncDatasourceVO plugin = plugin(type, role);
            if (plugin != null) {
                return plugin.getName();
            }
        }
        if ("sqlserver".equalsIgnoreCase(type)) {
            return "SQL Server";
        }
        return TYPE_NAMES.getOrDefault(type.toLowerCase(Locale.ROOT), type);
    }
}
