package io.dataease.ee.platform.lark;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.platform.EePlatformDeps;
import io.dataease.ee.sso.EeSsoConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书（open.feishu.cn）与国际飞书（open.larksuite.com）
 */
@Configuration
public class EeLarkConfig {

    @Bean
    public EeLarkManage eeLarkManage(EePlatformDeps deps, @Value("${dataease.ee.platform.lark.api:https://open.feishu.cn}") String base) {
        return new EeLarkManage(deps, EeSsoConstants.LARK, EeConstants.ORIGIN_LARK, base);
    }

    @Bean
    public EeLarkManage eeLarksuiteManage(EePlatformDeps deps, @Value("${dataease.ee.platform.larksuite.api:https://open.larksuite.com}") String base) {
        return new EeLarkManage(deps, EeSsoConstants.LARKSUITE, EeConstants.ORIGIN_LARKSUITE, base);
    }
}
