package io.dataease.ee.platform.wecom;

import io.dataease.api.wecom.api.WecomApi;
import io.dataease.api.wecom.dto.WecomCreator;
import io.dataease.api.wecom.dto.WecomEnableEditor;
import io.dataease.api.wecom.dto.WecomTokenRequest;
import io.dataease.api.wecom.vo.WecomInfoVO;
import io.dataease.ee.platform.EePlatformAccess;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业微信设置；qrinfo、token 为白名单，bind 需登录
 */
@RestController("wecomServer")
@RequestMapping("/wecom")
public class WecomServer implements WecomApi {

    private final EeWecomManage manage;

    public WecomServer(EeWecomManage manage) {
        this.manage = manage;
    }

    @Override
    public WecomInfoVO info() {
        EePlatformAccess.checkAdmin();
        return manage.info();
    }

    @Override
    public WecomInfoVO qrinfo() {
        return manage.qrinfo();
    }

    @Override
    public void save(WecomCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.save(convert(creator), creator.getEnable(), creator.getValid());
    }

    @Override
    public String wecomToken(WecomTokenRequest request) {
        return manage.token(request.getCode(), request.getState());
    }

    @Override
    public void switchEnable(WecomEnableEditor editor) {
        EePlatformAccess.checkAdmin();
        manage.switchEnable(editor.isEnable());
    }

    @Override
    public void validate(WecomCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.validate(convert(creator));
    }

    @Override
    public void bind(WecomTokenRequest request) {
        manage.bind(request.getCode(), request.getState());
    }

    private WecomInfoVO convert(WecomCreator creator) {
        WecomInfoVO vo = new WecomInfoVO();
        vo.setCorpId(StringUtils.trim(creator.getCorpId()));
        vo.setAgentId(StringUtils.trim(creator.getAgentId()));
        vo.setAppSecret(StringUtils.trim(creator.getAppSecret()));
        vo.setCallBack(StringUtils.trim(creator.getCallBack()));
        return vo;
    }
}
