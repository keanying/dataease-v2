package io.dataease.ee.setting.appearance;

import io.dataease.api.xpack.appearance.api.XpackAppearanceApi;
import io.dataease.api.xpack.appearance.vo.AppearanceItemVO;
import io.dataease.ee.setting.EeSettingSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 外观设置（查询/保存仅系统管理员，图片公开）
 */
@RestController("xpackAppearanceServer")
@RequestMapping("/appearance")
public class AppearanceServer implements XpackAppearanceApi {

    private final EeAppearanceManage appearanceManage;

    public AppearanceServer(EeAppearanceManage appearanceManage) {
        this.appearanceManage = appearanceManage;
    }

    @Override
    public void save(List<AppearanceItemVO> items, List<MultipartFile> files) {
        EeSettingSupport.checkSysAdmin();
        appearanceManage.save(items, files);
    }

    @Override
    public List<AppearanceItemVO> query() {
        EeSettingSupport.checkSysAdmin();
        return appearanceManage.query();
    }

    @Override
    public ResponseEntity<byte[]> image(String imageId) {
        return appearanceManage.image(imageId);
    }
}
