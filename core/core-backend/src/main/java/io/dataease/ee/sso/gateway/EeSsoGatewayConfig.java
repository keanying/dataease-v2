package io.dataease.ee.sso.gateway;

import io.dataease.ee.sso.cas.EeCasManage;
import io.dataease.ee.sso.oidc.EeOidcManage;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 OIDC / CAS 网关前缀过滤器（早于身份过滤器 eeTokenFilter 执行）
 */
@Configuration
public class EeSsoGatewayConfig {

    @Bean
    public FilterRegistrationBean<EeSsoGatewayFilter> eeSsoGatewayFilter(EeSsoGatewaySupport support, EeOidcManage oidcManage, EeCasManage casManage) {
        FilterRegistrationBean<EeSsoGatewayFilter> registration = new FilterRegistrationBean<>();
        registration.setName("eeSsoGatewayFilter");
        registration.setFilter(new EeSsoGatewayFilter(support, oidcManage, casManage));
        registration.addUrlPatterns("/oidcbi/*", "/casbi/*", "/oidcbi", "/casbi");
        registration.setOrder(0);
        return registration;
    }
}
