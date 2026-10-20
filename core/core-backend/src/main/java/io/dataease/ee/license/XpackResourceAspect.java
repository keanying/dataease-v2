package io.dataease.ee.license;

import io.dataease.exception.DEException;
import io.dataease.license.utils.LicenseUtil;
import io.dataease.result.ResultCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 未启用企业功能时拒绝访问 {@code @XpackResource} 标记的接口。
 */
@Aspect
@Component
public class XpackResourceAspect {

    @Around("@within(io.dataease.license.config.XpackResource) || @annotation(io.dataease.license.config.XpackResource)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        if (!LicenseUtil.licenseValid()) {
            DEException.throwException(ResultCode.INTERFACE_FORBID_VISIT.code(), ResultCode.INTERFACE_FORBID_VISIT.message());
        }
        return point.proceed();
    }
}
