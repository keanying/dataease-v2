package io.dataease.ee.plugin;

import io.dataease.api.xpack.component.XpackComponentApi;
import io.dataease.api.xpack.component.vo.XpackMenuVO;
import io.dataease.ee.datafilling.jdbc.DfJdbcSupport;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.vo.XpackPluginsDfVO;
import io.dataease.extensions.datasource.vo.XpackPluginsDatasourceVO;
import io.dataease.extensions.sync.vo.XpackPluginsSyncDatasourceVO;
import io.dataease.extensions.view.vo.XpackPluginsViewVO;
import io.dataease.result.ResultMessage;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件目录与插件前端资源
 */
@RestController("xpackComponentServer")
@RequestMapping("/xpackComponent")
public class XpackComponentServer implements XpackComponentApi {

    private final EePluginCatalog catalog;

    private final EePluginManage pluginManage;

    private final DfJdbcSupport dfJdbcSupport;

    public XpackComponentServer(EePluginCatalog catalog, EePluginManage pluginManage, @Lazy DfJdbcSupport dfJdbcSupport) {
        this.catalog = catalog;
        this.pluginManage = pluginManage;
        this.dfJdbcSupport = dfJdbcSupport;
    }

    @Override
    public String content(String name) {
        return null;
    }

    @Override
    public String pluginContent(String name) {
        return null;
    }

    @Override
    public List<XpackMenuVO> menu() {
        return new ArrayList<>();
    }

    @Override
    public List<XpackPluginsViewVO> viewPlugins() {
        return catalog.queryPluginView();
    }

    @Override
    public List<XpackPluginsDatasourceVO> dsPlugins() {
        return catalog.queryPluginDs();
    }

    @Override
    public List<XpackPluginsDfVO> dfPlugins() {
        return catalog.queryPluginDf();
    }

    @Override
    public XpackPluginsDfVO dfPlugin(String type) {
        XpackPluginsDfVO vo = catalog.queryPluginDf(type);
        // 数据填报内置支持的存储类型（PostgreSQL、SQL Server、Oracle 等）同样显示“开启数据填报”开关
        return vo != null ? vo : dfJdbcSupport.builtinPlugin(type);
    }

    @Override
    public List<XpackPluginsSyncDatasourceVO> syncPlugins() {
        return catalog.queryPluginSyncDatasource();
    }

    /**
     * 插件前端入口脚本 {@code static/{moduleName}.js}。moduleName 以 {@code extensions-} 开头时直接输出脚本，否则包装为统一响应。
     */
    @Override
    public void pluginStaticInfo(String moduleName) {
        EePluginManage.LoadedPlugin plugin = pluginManage.loadedByModule(moduleName);
        if (plugin == null) {
            DEException.throwException("plugin not found: " + moduleName);
        }
        String script;
        try (InputStream in = plugin.plugin().getStaticInfo(moduleName + ".js")) {
            if (in == null) {
                DEException.throwException("plugin static resource not found: " + moduleName);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DEException.throwException(e);
            return;
        }
        HttpServletResponse response = ServletUtils.response();
        if (response == null) {
            return;
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            if (StringUtils.startsWith(moduleName, "extensions-")) {
                response.setContentType("application/javascript;charset=UTF-8");
                response.getWriter().write(script);
            } else {
                response.setContentType("application/json;charset=UTF-8");
                Object body = JsonUtil.toJSONString(ResultMessage.success(script));
                response.getWriter().write(body == null ? "" : body.toString());
            }
            response.flushBuffer();
        } catch (IOException e) {
            DEException.throwException(e);
        }
    }
}
