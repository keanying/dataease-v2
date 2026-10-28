package io.dataease.ee.datasource.dm;

import io.dataease.ee.plugin.EePluginCatalog;
import io.dataease.extensions.datasource.api.PluginManageApi;
import io.dataease.extensions.datasource.vo.XpackPluginsDatasourceVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心通过 {@link PluginManageApi#queryPluginDs()} 获取非内置类型数据源的名称、分类与标识符引号
 * （数据集 SQL、图表 SQL、数据源校验等）。在已安装插件之外追加内置的达梦数据源。
 *
 * <p>前端“新建数据源”中的插件列表（{@code /xpackComponent/dsPlugins}）仍只返回已安装的插件包，
 * 内置达梦以普通类型出现在数据源类型列表中。</p>
 */
@Primary
@Component
public class DmPluginManageApi implements PluginManageApi {

    private final EePluginCatalog catalog;

    private final DmDatasourceProvider dmProvider;

    public DmPluginManageApi(EePluginCatalog catalog, DmDatasourceProvider dmProvider) {
        this.catalog = catalog;
        this.dmProvider = dmProvider;
    }

    @Override
    public List<XpackPluginsDatasourceVO> queryPluginDs() {
        List<XpackPluginsDatasourceVO> list = new ArrayList<>(catalog.queryPluginDs());
        boolean hasDm = list.stream().anyMatch(vo -> StringUtils.equalsIgnoreCase(vo.getType(), DmDatasourceProvider.TYPE));
        if (!hasDm && DmDatasourceProvider.builtinActive()) {
            list.add(dmProvider.getConfig());
        }
        return list;
    }
}
