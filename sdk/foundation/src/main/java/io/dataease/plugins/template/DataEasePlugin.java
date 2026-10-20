package io.dataease.plugins.template;

import io.dataease.plugins.utils.PluginJarReader;
import io.dataease.plugins.vo.DataEasePluginVO;

import java.io.InputStream;

/**
 * 插件契约。
 *
 * <p>插件包（jar）约定：</p>
 * <ul>
 *     <li>{@code plugin/extensions*.json}：插件描述，至少包含 {@code moduleName}、{@code name}、{@code version} 与 {@code config}；</li>
 *     <li>{@code plugin/{moduleName}.svg}：插件图标（可选）；</li>
 *     <li>{@code static/**}：前端静态资源，入口为 {@code static/{moduleName}.js}（可选）。</li>
 * </ul>
 */
public interface DataEasePlugin {

    /**
     * 读取插件描述文件原始内容
     */
    default String readPluginInfo() throws Exception {
        return PluginJarReader.readDescriptor(this.getClass());
    }

    /**
     * 解析插件描述
     */
    default DataEasePluginVO getPluginInfo() throws Exception {
        return PluginJarReader.parseDescriptor(this.getClass());
    }

    /**
     * 读取插件内的前端静态资源
     *
     * @param path 相对 {@code static/} 目录的路径
     */
    default InputStream getStaticInfo(String path) {
        return PluginJarReader.readStatic(this.getClass(), path);
    }

    void loadPlugin();

    void unloadPlugin();
}
