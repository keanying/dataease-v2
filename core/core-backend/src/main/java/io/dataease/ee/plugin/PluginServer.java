package io.dataease.ee.plugin;

import io.dataease.api.xpack.plugin.PluginApi;
import io.dataease.api.xpack.plugin.dto.PluginEditor;
import io.dataease.api.xpack.plugin.vo.PluginVO;
import io.dataease.ee.plugin.dao.XpackPlugin;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 插件管理（仅系统管理员）
 */
@RestController("pluginServer")
@RequestMapping("/plugin")
public class PluginServer implements PluginApi {

    private final EePluginManage pluginManage;

    public PluginServer(EePluginManage pluginManage) {
        this.pluginManage = pluginManage;
    }

    private void check() {
        if (!V3UserUtil.isSysAdmin()) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    @Override
    public List<PluginVO> query() {
        check();
        return pluginManage.list().stream().map(this::convert).toList();
    }

    private PluginVO convert(XpackPlugin po) {
        PluginVO vo = new PluginVO();
        vo.setId(po.getId());
        vo.setName(po.getName());
        vo.setFlag(po.getFlag());
        vo.setIcon(po.getIcon());
        vo.setVersion(po.getVersion());
        vo.setInstallTime(po.getInstallTime());
        vo.setDeveloper(po.getDeveloper());
        return vo;
    }

    @Override
    public void install(MultipartFile file) {
        check();
        pluginManage.install(file, null);
    }

    @Override
    public void uninstall(String id) {
        check();
        pluginManage.uninstall(Long.valueOf(id));
    }

    @Override
    public void update(PluginEditor request, MultipartFile file) {
        check();
        pluginManage.install(file, request == null || request.getId() == null ? null : Long.valueOf(request.getId()));
    }
}
