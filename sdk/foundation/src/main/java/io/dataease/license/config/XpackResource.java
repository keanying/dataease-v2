package io.dataease.license.config;

import java.lang.annotation.*;

/**
 * 标记仅企业版可访问的接口。未启用企业功能时，切面拒绝访问。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface XpackResource {

    String[] value() default {};

    String[] excludes() default {};
}
