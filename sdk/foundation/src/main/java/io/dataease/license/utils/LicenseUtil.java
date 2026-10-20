package io.dataease.license.utils;

import io.dataease.license.bo.F2CLicResult;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 企业功能开关。
 *
 * <p>自研企业版不再校验外部许可证：启动时由 core-backend 根据配置
 * {@code dataease.ee.enabled}（默认 {@code true}）写入当前状态。</p>
 */
public class LicenseUtil {

    private static volatile F2CLicResult RESULT;

    public static void set(F2CLicResult result) {
        RESULT = result;
    }

    public static F2CLicResult get() {
        return RESULT;
    }

    public static boolean licenseValid() {
        F2CLicResult result = RESULT;
        return result != null && result.getStatus() == F2CLicResult.Status.valid;
    }

    /**
     * 与 {@link #licenseValid()} 相同，保留旧方法名供定时任务等调用方使用。
     */
    public static boolean validate() {
        return licenseValid();
    }

    public static HttpServletResponse response() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getResponse();
        }
        return null;
    }

    public static void writeResult(ResultMessage resultMessage) {
        HttpServletResponse response = response();
        if (response == null) {
            return;
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        try {
            Object json = JsonUtil.toJSONString(resultMessage);
            response.getWriter().write(json == null ? "" : json.toString());
        } catch (IOException e) {
            LogUtil.error(e.getMessage(), e);
        }
    }
}
