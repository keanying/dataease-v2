package io.dataease.ee.message;

import io.dataease.ee.setting.EeSettingManage;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 消息附件/图片的临时存储，对外通过白名单接口 {@code /communicate/image/{id}}、
 * {@code /communicate/down/{id}/{fileName}/{suffix}} 访问（第三方平台消息中引用图片/附件链接时使用）。
 *
 * <p>文件 ID 为 32 位随机十六进制串（不可猜测）；文件保留天数沿用“导出文件保留时间”（basic.exportFileLiveTime，默认 30 天）。</p>
 */
@Component
public class EeCommunicateFileStore {

    private static final Pattern ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    @Value("${dataease.path.communicate:/opt/dataease3.0/data/communicate/}")
    private String root;

    private final EeSettingManage settingManage;

    public EeCommunicateFileStore(EeSettingManage settingManage) {
        this.settingManage = settingManage;
    }

    /**
     * 保存文件，返回文件ID
     */
    public String save(byte[] body) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        Path dir = Path.of(root);
        Files.createDirectories(dir);
        Files.write(dir.resolve(id), body == null ? new byte[0] : body);
        return id;
    }

    /**
     * 读取文件；ID 非法或不存在时返回 null
     */
    public byte[] read(String id) {
        if (StringUtils.isBlank(id) || !ID_PATTERN.matcher(id).matches()) {
            return null;
        }
        Path file = Path.of(root).resolve(id);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LogUtil.warn("read communicate file failed: " + id + " " + e.getMessage());
            return null;
        }
    }

    @Scheduled(cron = "0 41 2 * * ?")
    public void cleanExpired() {
        File dir = new File(root);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        int days = Math.max(1, settingManage.getInt("basic.exportFileLiveTime", 30));
        long threshold = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        for (File file : files) {
            if (file.isFile() && ID_PATTERN.matcher(file.getName()).matches() && file.lastModified() < threshold) {
                if (!file.delete()) {
                    LogUtil.warn("delete communicate file failed: " + file.getName());
                }
            }
        }
    }
}
