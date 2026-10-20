package io.dataease.ee.permissions.auth;

import io.dataease.auth.DePermit;
import io.dataease.ee.permissions.menu.EeMenuManage;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class DePermitConfig {

    @Bean
    public Advisor dePermitAdvisor(@Lazy EePermissionManage permissionManage, @Lazy EeEntityPermit entityPermit, @Lazy EeMenuManage menuManage) {
        AnnotationMatchingPointcut pointcut = new AnnotationMatchingPointcut(null, DePermit.class, true);
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, new DePermitInterceptor(permissionManage, entityPermit, menuManage));
        advisor.setOrder(5);
        return advisor;
    }
}
