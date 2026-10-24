package io.dataease.ee.platform.dingtalk;

import io.dataease.api.dingtalk.api.DingtalkApi;
import io.dataease.api.dingtalk.dto.*;
import io.dataease.api.dingtalk.vo.DingtalkInfoVO;
import io.dataease.api.lark.vo.LarkGroupVO;
import io.dataease.ee.platform.EePlatformAccess;
import io.dataease.ee.sso.support.EeJson;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钉钉设置；qrinfo、token 为白名单，bind 需登录，getGroup 供定时报告使用
 */
@RestController("dingtalkServer")
@RequestMapping("/dingtalk")
public class DingtalkServer implements DingtalkApi {

    private final EeDingtalkManage manage;

    public DingtalkServer(EeDingtalkManage manage) {
        this.manage = manage;
    }

    @Override
    public DingtalkInfoVO info() {
        EePlatformAccess.checkAdmin();
        return manage.info();
    }

    @Override
    public DingtalkInfoVO qrinfo() {
        return manage.qrinfo();
    }

    @Override
    public void save(DingtalkSettingCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.save(convert(creator), creator.getEnable(), creator.getValid());
    }

    @Override
    public String dingtalkToken(DingtalkTokenRequest request) {
        return manage.token(request.getCode(), request.getState());
    }

    @Override
    public void switchEnable(DingtalkEnableEditor editor) {
        EePlatformAccess.checkAdmin();
        manage.switchEnable(editor.isEnable());
    }

    @Override
    public void validate(DingtalkSettingCreator creator) {
        EePlatformAccess.checkAdmin();
        manage.validate(convert(creator));
    }

    @Override
    public void bind(DingtalkTokenRequest request) {
        manage.bind(request.getCode(), request.getState());
    }

    @Override
    public LarkGroupVO getGroup() {
        EePlatformAccess.checkLogin();
        return manage.getGroup();
    }

    @Override
    public DingtalkSignatureInfo getSignatureInfo(SignatureRequest request) {
        EePlatformAccess.checkAdmin();
        return manage.signature(request.getCurrentUrl());
    }

    @Override
    public void checkChat(DingtalkChatCheckRequest request) {
        EePlatformAccess.checkAdmin();
        manage.checkChat(request.getChatId());
    }

    private DingtalkInfoVO convert(DingtalkSettingCreator creator) {
        DingtalkInfoVO vo = EeJson.convert(creator, DingtalkInfoVO.class);
        vo.setCorpId(trim(vo.getCorpId()));
        vo.setAgentId(trim(vo.getAgentId()));
        vo.setAppKey(trim(vo.getAppKey()));
        vo.setAppSecret(trim(vo.getAppSecret()));
        vo.setCallBack(trim(vo.getCallBack()));
        vo.setRobotCode(trim(vo.getRobotCode()));
        return vo;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
