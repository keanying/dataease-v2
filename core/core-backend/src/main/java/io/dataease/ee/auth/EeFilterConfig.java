package io.dataease.ee.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EeFilterConfig {

    @Bean
    public FilterRegistrationBean<EeTokenFilter> eeTokenFilter(List<EeRequestAuthenticator> authenticators) {
        List<EeRequestAuthenticator> sorted = new ArrayList<>(authenticators);
        AnnotationAwareOrderComparator.sort(sorted);
        FilterRegistrationBean<EeTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setName("eeTokenFilter");
        registration.setFilter(new EeTokenFilter(sorted));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
