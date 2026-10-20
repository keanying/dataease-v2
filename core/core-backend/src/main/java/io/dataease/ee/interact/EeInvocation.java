package io.dataease.ee.interact;

/**
 * recursion 模式下传给扩展方法的原方法调用器。
 */
@FunctionalInterface
public interface EeInvocation {

    /**
     * 以原始参数执行被拦截的方法
     */
    Object proceed() throws Throwable;
}
