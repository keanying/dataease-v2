package io.dataease.ee.interact;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * 声明一个企业版扩展 Bean，{@link #value()} 与开源核心 {@code @XpackInteract(value)} 对应。
 *
 * <p>扩展方法与被拦截方法同名，参数约定见 {@link XpackInteractAspect}。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface EeExtension {

    /**
     * 对应 {@code @XpackInteract} 的 value，一个扩展 Bean 可以对应多个
     */
    String[] value();
}
