package io.dataease.ee.plugin;

import io.dataease.ee.plugin.dao.XpackPlugin;
import io.dataease.ee.plugin.dao.XpackPluginRepository;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.plugin.DataFillingPlugin;
import io.dataease.extensions.datasource.plugin.DataEaseDatasourcePlugin;
import io.dataease.extensions.sync.plugin.DataEaseSyncDatasourcePlugin;
import io.dataease.extensions.view.plugin.DataEaseChartPlugin;
import io.dataease.plugins.factory.DataEasePluginFactory;
import io.dataease.plugins.template.DataEasePlugin;
import io.dataease.plugins.utils.PluginJarReader;
import io.dataease.plugins.vo.DataEasePluginVO;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件管理：插件包存放在 {@code dataease.path.plugin} 目录，启动时加载。
 *
 * <p>插件入口类的查找顺序：{@code META-INF/services/io.dataease.plugins.template.DataEasePlugin}、描述文件中的
 * {@code className}、扫描插件包内实现了 {@link DataEasePlugin} 的具体类。入口类由 Spring 创建，支持依赖注入。</p>
 */
@Component
public class EePluginManage {

    private static final String SERVICE_FILE = "META-INF/services/" + DataEasePlugin.class.getName();

    private final XpackPluginRepository repository;

    private final ApplicationContext applicationContext;

    @Value("${dataease.path.plugin:/opt/dataease3.0/data/plugin}")
    private String pluginDir;

    /**
     * moduleName -> 已加载插件
     */
    private final Map<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();

    public EePluginManage(XpackPluginRepository repository, ApplicationContext applicationContext) {
        this.repository = repository;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void ensureDir() {
        try {
            Files.createDirectories(Paths.get(pluginDir));
        } catch (Exception e) {
            LogUtil.warn("create plugin dir failed: " + e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() {
        for (XpackPlugin plugin : repository.findAll()) {
            File file = Paths.get(pluginDir, plugin.getFileName()).toFile();
            if (!file.isFile()) {
                LogUtil.warn("plugin file missing: " + file.getAbsolutePath());
                continue;
            }
            try {
                load(file);
            } catch (Throwable e) {
                LogUtil.error("load plugin " + plugin.getName() + " failed: " + e.getMessage(), e);
            }
        }
    }

    public List<XpackPlugin> list() {
        return repository.findAll().stream().sorted(Comparator.comparing(item -> item.getInstallTime() == null ? 0L : item.getInstallTime())).toList();
    }

    public Collection<LoadedPlugin> loadedPlugins() {
        return loaded.values();
    }

    public LoadedPlugin loadedByModule(String moduleName) {
        return moduleName == null ? null : loaded.get(moduleName);
    }

    /**
     * 安装插件：校验插件包、保存文件、登记并立即加载
     */
    public synchronized void install(MultipartFile file, Long replaceId) {
        if (file == null || file.isEmpty() || !StringUtils.endsWithIgnoreCase(file.getOriginalFilename(), ".jar")) {
            DEException.throwException("仅支持 jar 格式的插件包");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(Paths.get(pluginDir), "upload-", ".jar");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            DataEasePluginVO info = readDescriptor(temp.toFile());
            XpackPlugin exist = repository.findAll().stream().filter(item -> StringUtils.equals(item.getModuleName(), info.getModuleName()))
                    .findFirst().orElse(null);
            if (replaceId != null && (exist == null || !exist.getId().equals(replaceId))) {
                DEException.throwException("插件包与待更新插件不一致");
            }
            if (exist != null) {
                unload(exist.getModuleName());
                Files.deleteIfExists(Paths.get(pluginDir, exist.getFileName()));
                repository.deleteById(exist.getId());
            }
            String fileName = safeName(info.getModuleName()) + "-" + StringUtils.defaultIfBlank(info.getVersion(), "0") + ".jar";
            Path target = Paths.get(pluginDir, fileName);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            LoadedPlugin plugin = load(target.toFile());
            XpackPlugin po = new XpackPlugin();
            po.setId(exist == null ? IDUtils.snowID() : exist.getId());
            po.setName(StringUtils.defaultIfBlank(info.getName(), info.getModuleName()));
            po.setFlag(plugin.flag());
            po.setModuleName(info.getModuleName());
            po.setVersion(info.getVersion());
            po.setRequireVersion(info.getRequireVersion());
            po.setDeveloper(info.getDeveloper());
            po.setIcon(info.getIcon());
            po.setFileName(fileName);
            po.setInstallTime(System.currentTimeMillis());
            repository.save(po);
        } catch (DEException e) {
            throw e;
        } catch (Throwable e) {
            LogUtil.error(e.getMessage(), e);
            DEException.throwException("插件安装失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    public synchronized void uninstall(Long id) {
        XpackPlugin plugin = repository.findById(id).orElse(null);
        if (plugin == null) {
            return;
        }
        unload(plugin.getModuleName());
        try {
            Files.deleteIfExists(Paths.get(pluginDir, plugin.getFileName()));
        } catch (Exception e) {
            LogUtil.warn("delete plugin file failed: " + e.getMessage());
        }
        repository.deleteById(id);
    }

    private void unload(String moduleName) {
        LoadedPlugin plugin = loaded.remove(moduleName);
        if (plugin == null) {
            return;
        }
        try {
            plugin.plugin().unloadPlugin();
        } catch (Throwable e) {
            LogUtil.warn("unload plugin " + moduleName + " failed: " + e.getMessage());
        }
        DataEasePluginFactory.remove(moduleName);
        try {
            plugin.classLoader().close();
        } catch (Exception ignored) {
            // 关闭类加载器失败不影响卸载
        }
    }

    private DataEasePluginVO readDescriptor(File jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader())) {
            Class<?> entry = findEntry(jar, loader);
            return PluginJarReader.parseDescriptor(entry);
        }
    }

    private LoadedPlugin load(File jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, getClass().getClassLoader());
        try {
            Class<?> entry = findEntry(jar, loader);
            DataEasePlugin plugin = (DataEasePlugin) applicationContext.getAutowireCapableBeanFactory().createBean(entry);
            DataEasePluginVO info = plugin.getPluginInfo();
            plugin.loadPlugin();
            DataEasePluginFactory.loadTemplate(info.getModuleName(), plugin);
            LoadedPlugin loadedPlugin = new LoadedPlugin(info, plugin, loader, flagOf(plugin));
            loaded.put(info.getModuleName(), loadedPlugin);
            return loadedPlugin;
        } catch (Throwable e) {
            loader.close();
            throw e;
        }
    }

    private Class<?> findEntry(File jar, ClassLoader loader) throws Exception {
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry service = jarFile.getJarEntry(SERVICE_FILE);
            if (service != null) {
                try (InputStream in = jarFile.getInputStream(service)) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                        String className = StringUtils.substringBefore(line, "#").trim();
                        if (StringUtils.isNotBlank(className)) {
                            return loader.loadClass(className);
                        }
                    }
                }
            }
            Enumeration<JarEntry> entries = jarFile.entries();
            List<String> candidates = new ArrayList<>();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class") && !name.contains("$") && !name.startsWith("META-INF/")) {
                    candidates.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
            for (String className : candidates) {
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    if (DataEasePlugin.class.isAssignableFrom(clazz) && !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())
                            && clazz.getClassLoader() == loader) {
                        return clazz;
                    }
                } catch (Throwable ignored) {
                    // 无法解析的类跳过
                }
            }
        }
        DEException.throwException("插件包中未找到插件入口类");
        return null;
    }

    private String flagOf(DataEasePlugin plugin) {
        if (plugin instanceof DataEaseDatasourcePlugin) {
            return "ds";
        }
        if (plugin instanceof DataEaseChartPlugin) {
            return "view";
        }
        if (plugin instanceof DataFillingPlugin) {
            return "df";
        }
        if (plugin instanceof DataEaseSyncDatasourcePlugin sync) {
            Integer role = sync.getConfig() == null ? null : sync.getConfig().getDatasourceRole();
            return role != null && role == 2 ? "sync-sink" : "sync-source";
        }
        return "other";
    }

    private String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record LoadedPlugin(DataEasePluginVO info, DataEasePlugin plugin, URLClassLoader classLoader, String flag) {
    }
}
