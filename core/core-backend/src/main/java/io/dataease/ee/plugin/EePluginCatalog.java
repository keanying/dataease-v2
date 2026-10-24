package io.dataease.ee.plugin;

import io.dataease.extensions.datafilling.api.DfPluginManageApi;
import io.dataease.extensions.datafilling.plugin.DataFillingPlugin;
import io.dataease.extensions.datafilling.vo.XpackPluginsDfVO;
import io.dataease.extensions.datasource.api.PluginManageApi;
import io.dataease.extensions.datasource.plugin.DataEaseDatasourcePlugin;
import io.dataease.extensions.datasource.vo.XpackPluginsDatasourceVO;
import io.dataease.extensions.sync.api.SyncPluginManageApi;
import io.dataease.extensions.sync.plugin.DataEaseSyncDatasourcePlugin;
import io.dataease.extensions.sync.vo.XpackPluginsSyncDatasourceVO;
import io.dataease.extensions.view.plugin.DataEaseChartPlugin;
import io.dataease.extensions.view.vo.XpackPluginsViewVO;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 已加载插件的目录：数据源、图表、数据填报、数据同步
 */
@Component
public class EePluginCatalog implements PluginManageApi, DfPluginManageApi, SyncPluginManageApi {

    private final EePluginManage pluginManage;

    public EePluginCatalog(EePluginManage pluginManage) {
        this.pluginManage = pluginManage;
    }

    @Override
    public List<XpackPluginsDatasourceVO> queryPluginDs() {
        return collect(DataEaseDatasourcePlugin.class, DataEaseDatasourcePlugin::getConfig);
    }

    public List<XpackPluginsViewVO> queryPluginView() {
        return collect(DataEaseChartPlugin.class, DataEaseChartPlugin::getConfig);
    }

    @Override
    public List<XpackPluginsDfVO> queryPluginDf() {
        return collect(DataFillingPlugin.class, DataFillingPlugin::getConfig);
    }

    @Override
    public XpackPluginsDfVO queryPluginDf(String type) {
        return queryPluginDf().stream().filter(item -> StringUtils.equals(item.getType(), type)).findFirst().orElse(null);
    }

    @Override
    public List<XpackPluginsSyncDatasourceVO> queryPluginSyncDatasource() {
        return collect(DataEaseSyncDatasourcePlugin.class, DataEaseSyncDatasourcePlugin::getConfig);
    }

    private <P, V> List<V> collect(Class<P> type, Function<P, V> config) {
        List<V> result = new ArrayList<>();
        for (EePluginManage.LoadedPlugin plugin : pluginManage.loadedPlugins()) {
            if (type.isInstance(plugin.plugin())) {
                try {
                    V vo = config.apply(type.cast(plugin.plugin()));
                    if (vo != null) {
                        result.add(vo);
                    }
                } catch (Exception e) {
                    LogUtil.warn("read plugin config failed: " + e.getMessage());
                }
            }
        }
        return result;
    }
}
