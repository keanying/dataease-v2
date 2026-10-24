package io.dataease.ee.platform.lark;

import io.dataease.api.lark.dto.LarkSettingCreator;
import io.dataease.api.lark.vo.LarkInfoVO;
import org.apache.commons.lang3.StringUtils;

final class LarkConverter {

    private LarkConverter() {
    }

    static LarkInfoVO convert(LarkSettingCreator creator) {
        LarkInfoVO vo = new LarkInfoVO();
        vo.setAppId(StringUtils.trim(creator.getAppId()));
        vo.setAppSecret(StringUtils.trim(creator.getAppSecret()));
        vo.setCallBack(StringUtils.trim(creator.getCallBack()));
        return vo;
    }
}
