package io.dataease.ee.platform.lark;

import io.dataease.api.lark.api.LarkApi;
import io.dataease.api.lark.dto.LarkEnableEditor;
import io.dataease.api.lark.dto.LarkSettingCreator;
import io.dataease.api.lark.dto.LarkTokenRequest;
import io.dataease.api.lark.vo.LarkGroupVO;
import io.dataease.api.lark.vo.LarkInfoVO;
import io.dataease.ee.platform.EePlatformAccess;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("larkServer")
@RequestMapping("/lark")
public class LarkServer implements LarkApi {

    private final EeLarkManage manage;

    public LarkServer(@Qualifier("eeLarkManage") EeLarkManage manage) {
        this.manage = manage;
    }

    @Override
    public LarkInfoVO info() {
        EePlatformAccess.checkAdmin();
        return manage.info();
    }

    @Override
    public LarkInfoVO qrinfo() {
        return manage.qrinfo();
    }

    @Override
    public void save(LarkSettingCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.save(LarkConverter.convert(creator), creator.getEnable(), creator.getValid());
    }

    @Override
    public String larkToken(LarkTokenRequest request) {
        return manage.token(request.getCode(), request.getState());
    }

    @Override
    public void switchEnable(LarkEnableEditor editor) {
        EePlatformAccess.checkAdmin();
        manage.switchEnable(editor.isEnable());
    }

    @Override
    public void validate(LarkSettingCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.validate(LarkConverter.convert(creator));
    }

    @Override
    public void bind(LarkTokenRequest request) {
        manage.bind(request.getCode(), request.getState());
    }

    @Override
    public LarkGroupVO getGroup() {
        EePlatformAccess.checkLogin();
        return manage.getGroup();
    }
}
