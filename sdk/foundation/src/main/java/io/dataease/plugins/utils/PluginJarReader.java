package io.dataease.plugins.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dataease.exception.DEException;
import io.dataease.license.utils.JsonUtil;
import io.dataease.plugins.vo.DataEasePluginVO;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * 从插件类所在的 jar（或开发期的 classes 目录）读取描述文件与静态资源。
 */
public final class PluginJarReader {

    private static final Pattern DESCRIPTOR = Pattern.compile("^plugin/extensions.*\\.json$");

    private PluginJarReader() {
    }

    public static String readDescriptor(Class<?> pluginClass) throws Exception {
        File location = location(pluginClass);
        if (location.isFile()) {
            try (JarFile jarFile = new JarFile(location)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (DESCRIPTOR.matcher(entry.getName()).matches()) {
                        try (InputStream in = jarFile.getInputStream(entry)) {
                            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } else if (location.isDirectory()) {
            File pluginDir = new File(location, "plugin");
            File[] files = pluginDir.listFiles((dir, name) -> DESCRIPTOR.matcher("plugin/" + name).matches());
            if (files != null && files.length > 0) {
                return Files.readString(files[0].toPath(), StandardCharsets.UTF_8);
            }
        }
        DEException.throwException("插件缺失描述文件[plugin/extensions-*.json]");
        return null;
    }

    public static DataEasePluginVO parseDescriptor(Class<?> pluginClass) throws Exception {
        String json = readDescriptor(pluginClass);
        Map<String, Object> info = JsonUtil.parseObject(json, new TypeReference<Map<String, Object>>() {
        });
        if (info == null || info.isEmpty()) {
            DEException.throwException("插件描述文件内容为空");
        }
        Object moduleName = info.get("moduleName");
        if (moduleName == null || StringUtils.isBlank(moduleName.toString())) {
            DEException.throwException("插件描述文件缺失 moduleName");
        }
        Object config = info.get("config");
        if (config != null && !(config instanceof String)) {
            info.put("config", JsonUtil.toJSONString(config));
        }
        if (info.get("icon") == null) {
            byte[] icon = readBytes(pluginClass, "plugin/" + moduleName + ".svg");
            if (icon != null) {
                info.put("icon", new String(icon, StandardCharsets.UTF_8));
            }
        }
        Object vo = JsonUtil.toJSONString(info);
        return JsonUtil.parseObject(vo == null ? null : vo.toString(), DataEasePluginVO.class);
    }

    public static InputStream readStatic(Class<?> pluginClass, String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.contains(":")) {
            DEException.throwException("Invalid static resource path");
        }
        byte[] bytes = readBytes(pluginClass, "static/" + normalized);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    private static byte[] readBytes(Class<?> pluginClass, String entryName) {
        try {
            File location = location(pluginClass);
            if (location.isFile()) {
                try (JarFile jarFile = new JarFile(location)) {
                    JarEntry entry = jarFile.getJarEntry(entryName);
                    if (entry == null) {
                        return null;
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
            Path file = location.toPath().resolve(entryName).normalize();
            if (file.startsWith(location.toPath()) && Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            return null;
        } catch (Exception e) {
            DEException.throwException(e);
            return null;
        }
    }

    private static File location(Class<?> pluginClass) throws Exception {
        CodeSource codeSource = pluginClass.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            DEException.throwException("无法定位插件包位置");
        }
        URI uri = codeSource.getLocation().toURI();
        return new File(uri);
    }
}
