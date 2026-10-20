package io.dataease.ee.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求身份识别器。{@link EeTokenFilter} 按 {@link org.springframework.core.Ordered} 顺序选用第一个支持当前请求的识别器。
 */
public interface EeRequestAuthenticator {

    /**
     * 当前请求是否携带本识别器负责的凭证
     */
    boolean supports(HttpServletRequest request);

    /**
     * 校验凭证并通过 {@link io.dataease.permission.util.V3UserUtil#setContext(Long, Long)} 设置身份；校验失败抛出异常
     */
    void authenticate(HttpServletRequest request);
}
