package io.dataease.plugins.factory;

import io.dataease.plugins.template.DataEasePlugin;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已加载插件注册表，以插件 moduleName 为键。
 */
public class DataEasePluginFactory {

    private static final Map<String, DataEasePlugin> PLUGINS = new ConcurrentHashMap<>();

    public static DataEasePlugin getInstance(String moduleName) {
        if (moduleName == null) {
            return null;
        }
        return PLUGINS.get(moduleName);
    }

    public static void loadTemplate(String moduleName, DataEasePlugin plugin) {
        if (moduleName == null || plugin == null) {
            return;
        }
        PLUGINS.put(moduleName, plugin);
    }

    public static DataEasePlugin remove(String moduleName) {
        if (moduleName == null) {
            return null;
        }
        return PLUGINS.remove(moduleName);
    }

    public static Map<String, DataEasePlugin> all() {
        return Collections.unmodifiableMap(PLUGINS);
    }
}
