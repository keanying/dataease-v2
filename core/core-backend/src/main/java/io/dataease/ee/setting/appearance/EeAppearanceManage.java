package io.dataease.ee.setting.appearance;

import io.dataease.api.xpack.appearance.vo.AppearanceItemVO;
import io.dataease.ee.setting.EeSettingConstants;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.system.dao.auto.entity.CoreSysSetting;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import jakarta.annotation.PostConstruct;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 外观设置：设置项保存在 core_sys_setting（键前缀 {@code ui.}），图片保存在 {@code dataease.path.appearance} 目录。
 *
 * <p>图片 ID 为雪花 ID，文件名为 {@code <id>.<扩展名>}。</p>
 */
@Component
public class EeAppearanceManage {

    /**
     * 图片类型的设置项
     */
    public static final Set<String> IMAGE_KEYS = Set.of("web", "login", "bg", "navigate", "mobileLogin", "mobileLoginBg");

    private static final Map<String, String> IMAGE_TYPES = new LinkedHashMap<>();

    static {
        IMAGE_TYPES.put("png", MediaType.IMAGE_PNG_VALUE);
        IMAGE_TYPES.put("jpg", MediaType.IMAGE_JPEG_VALUE);
        IMAGE_TYPES.put("jpeg", MediaType.IMAGE_JPEG_VALUE);
        IMAGE_TYPES.put("gif", MediaType.IMAGE_GIF_VALUE);
        IMAGE_TYPES.put("svg", "image/svg+xml");
    }

    private static final Pattern IMAGE_ID = Pattern.compile("^\\d{1,20}$");

    private static final Pattern PKEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    /**
     * 未保存时 /sysParameter/ui 返回的默认值（与前端默认值一致），保证列表非空，前端据此识别为企业版
     */
    private static final Map<String, String> UI_DEFAULTS = new LinkedHashMap<>();

    static {
        UI_DEFAULTS.put("navigateBg", "dark");
        UI_DEFAULTS.put("themeColor", "default");
        UI_DEFAULTS.put("name", "DataEase");
        UI_DEFAULTS.put("showSlogan", "true");
        UI_DEFAULTS.put("foot", "false");
        UI_DEFAULTS.put("help", "https://docs.fit2cloud.com/dataease/");
        UI_DEFAULTS.put("showDoc", "0");
        UI_DEFAULTS.put("showAbout", "0");
        UI_DEFAULTS.put("showAi", "0");
    }

    private final EeSettingManage settingManage;

    @Value("${dataease.path.appearance:/opt/dataease3.0/data/appearance}")
    private String imageDir;

    /**
     * 单个图片大小上限（字节）
     */
    @Value("${dataease.appearance.max-image-size:10485760}")
    private long maxImageSize;

    public EeAppearanceManage(EeSettingManage settingManage) {
        this.settingManage = settingManage;
    }

    @PostConstruct
    public void ensureDir() {
        try {
            Files.createDirectories(Paths.get(imageDir));
        } catch (Exception e) {
            LogUtil.warn("create appearance dir failed: " + e.getMessage());
        }
    }

    public List<AppearanceItemVO> query() {
        List<AppearanceItemVO> result = new ArrayList<>();
        for (CoreSysSetting setting : settingManage.group(EeSettingConstants.APPEARANCE_PREFIX)) {
            AppearanceItemVO vo = new AppearanceItemVO();
            vo.setPkey(setting.getPkey().substring(EeSettingConstants.APPEARANCE_PREFIX.length()));
            vo.setPval(setting.getPval());
            vo.setType(setting.getType());
            vo.setSort(setting.getSort());
            result.add(vo);
        }
        return result;
    }

    /**
     * /sysParameter/ui 使用：已保存项 + 默认值，不含 community 项
     */
    public List<Object> uiList() {
        Map<String, String> values = new LinkedHashMap<>(UI_DEFAULTS);
        for (AppearanceItemVO item : query()) {
            if (IMAGE_KEYS.contains(item.getPkey()) && StringUtils.isBlank(item.getPval())) {
                continue;
            }
            values.put(item.getPkey(), item.getPval());
        }
        List<Object> result = new ArrayList<>();
        values.forEach((k, v) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("pkey", k);
            map.put("pval", v);
            result.add(map);
        });
        return result;
    }

    @Transactional
    public void save(List<AppearanceItemVO> items, List<MultipartFile> files) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        Map<String, MultipartFile> fileMap = new HashMap<>();
        if (files != null) {
            for (MultipartFile file : files) {
                String name = StringUtils.defaultString(file.getOriginalFilename());
                int idx = name.lastIndexOf(',');
                if (idx < 0) {
                    continue;
                }
                fileMap.put(name.substring(idx + 1).trim(), file);
            }
        }
        Map<String, String> current = new HashMap<>();
        for (AppearanceItemVO vo : query()) {
            current.put(vo.getPkey(), vo.getPval());
        }
        List<String> newImages = new ArrayList<>();
        List<String> obsoleteImages = new ArrayList<>();
        try {
            for (AppearanceItemVO item : items) {
                String pkey = StringUtils.trimToEmpty(item.getPkey());
                if (!PKEY.matcher(pkey).matches()) {
                    DEException.throwException(Translator.get("ee_settings.invalid_param", pkey));
                }
                String key = EeSettingConstants.APPEARANCE_PREFIX + pkey;
                if (IMAGE_KEYS.contains(pkey) || StringUtils.equals(item.getType(), "file")) {
                    if (!IMAGE_KEYS.contains(pkey)) {
                        DEException.throwException(Translator.get("ee_settings.invalid_param", pkey));
                    }
                    MultipartFile file = fileMap.get(pkey);
                    String old = current.get(pkey);
                    if (file != null) {
                        String imageId = store(file);
                        newImages.add(imageId);
                        settingManage.put(key, imageId, "file", sort(item));
                        if (StringUtils.isNotBlank(old)) {
                            obsoleteImages.add(old);
                        }
                    } else if (StringUtils.isBlank(item.getPval())) {
                        // 恢复默认
                        settingManage.delete(key);
                        if (StringUtils.isNotBlank(old)) {
                            obsoleteImages.add(old);
                        }
                    }
                    // 有 pval 但没有对应文件：保持原值
                    continue;
                }
                settingManage.put(key, StringUtils.defaultString(item.getPval()), StringUtils.defaultIfBlank(item.getType(), "text"), sort(item));
            }
        } catch (RuntimeException e) {
            newImages.forEach(this::deleteImage);
            throw e;
        }
        obsoleteImages.forEach(this::deleteImage);
    }

    private Integer sort(AppearanceItemVO item) {
        return item.getSort() == null ? 1 : item.getSort();
    }

    private String store(MultipartFile file) {
        String name = StringUtils.defaultString(file.getOriginalFilename());
        int idx = name.lastIndexOf(',');
        String origin = idx < 0 ? name : name.substring(0, idx);
        String ext = StringUtils.lowerCase(StringUtils.substringAfterLast(origin, "."));
        if (!IMAGE_TYPES.containsKey(ext)) {
            DEException.throwException(Translator.get("ee_settings.image_type_invalid", origin));
        }
        if (file.getSize() > maxImageSize) {
            DEException.throwException(Translator.get("ee_settings.image_too_large", maxImageSize / 1024 / 1024 + "MB"));
        }
        String id = String.valueOf(IDUtils.snowID());
        Path target = Paths.get(imageDir, id + "." + ext);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LogUtil.error("save appearance image failed", e);
            DEException.throwException(Translator.get("ee_settings.image_save_failed"));
        }
        return id;
    }

    private Path find(String imageId) {
        if (StringUtils.isBlank(imageId) || !IMAGE_ID.matcher(imageId).matches()) {
            return null;
        }
        for (String ext : IMAGE_TYPES.keySet()) {
            Path path = Paths.get(imageDir, imageId + "." + ext);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private void deleteImage(String imageId) {
        Path path = find(imageId);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LogUtil.warn("delete appearance image failed: " + e.getMessage());
        }
    }

    public ResponseEntity<byte[]> image(String imageId) {
        Path path = find(imageId);
        if (path == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String fileName = path.getFileName().toString();
        String ext = StringUtils.substringAfterLast(fileName, ".");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, IMAGE_TYPES.get(ext));
        headers.set("X-Content-Type-Options", "nosniff");
        if (StringUtils.equals(ext, "svg")) {
            // 禁止 SVG 中的脚本执行
            headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        }
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
        headers.setContentDisposition(org.springframework.http.ContentDisposition.inline()
                .filename(fileName, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }
}
