package io.dataease.license.config;

import java.lang.annotation.*;

/**
 * 标记开源核心中可被企业版扩展的方法。
 *
 * <p>{@link #value()} 为扩展 Bean 的逻辑名称，企业版在运行时通过切面把调用委托给
 * 同名方法签名的扩展实现。没有扩展实现时，方法照常执行自身逻辑。</p>
 *
 * <ul>
 *     <li>{@link #replace()}：扩展实现的返回值替换原方法结果，原方法不执行；</li>
 *     <li>{@link #before()}：为 {@code false} 时先执行原方法，再以原方法返回值作为附加参数调用扩展；</li>
 *     <li>{@link #recursion()}：扩展实现可以回调原方法（扩展方法额外接收原方法调用器）；</li>
 *     <li>{@link #invalid()}：未启用企业功能时也执行扩展；</li>
 *     <li>{@link #original()}：仅调用原方法，扩展只做旁路处理。</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface XpackInteract {

    String value();

    boolean replace() default false;

    boolean before() default true;

    boolean recursion() default false;

    boolean invalid() default false;

    boolean original() default false;
}
