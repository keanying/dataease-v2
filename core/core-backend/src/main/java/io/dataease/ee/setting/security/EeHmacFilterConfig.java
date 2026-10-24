package io.dataease.ee.setting.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 HMAC 校验过滤器（位于身份识别过滤器 eeTokenFilter 之后）
 */
@Configuration
public class EeHmacFilterConfig {

    @Bean
    public FilterRegistrationBean<EeHmacFilter> eeHmacFilter(EeHmacManage hmacManage) {
        FilterRegistrationBean<EeHmacFilter> registration = new FilterRegistrationBean<>();
        registration.setName("eeHmacFilter");
        registration.setFilter(new EeHmacFilter(hmacManage));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }
}
