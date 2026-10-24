package io.dataease.ee.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dataease.api.report.dto.ReportInstanceDelRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * 兼容前端清理日志请求 {@code {taskId:'', timeFlag:'1', clearType:'1'..'6'}}：
 * 前端不会修改 timeFlag，实际周期在 SDK DTO 没有的 clearType 中，这里把 clearType 映射到 timeFlag，
 * 并把空字符串 ID 视为未传。
 */
@ControllerAdvice(assignableTypes = ReportServer.class)
public class ReportDeleteLogBodyAdvice extends RequestBodyAdviceAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == ReportInstanceDelRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = inputMessage.getBody().readAllBytes();
        byte[] result = body;
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node instanceof ObjectNode object) {
                JsonNode clearType = object.get("clearType");
                if (clearType != null && !clearType.isNull() && !clearType.asText().isBlank()) {
                    object.put("timeFlag", clearType.asText().trim());
                }
                for (String key : new String[]{"taskId", "instanceId", "timeFlag"}) {
                    JsonNode value = object.get(key);
                    if (value != null && value.isTextual() && value.asText().isBlank()) {
                        object.remove(key);
                    }
                }
                object.remove("clearType");
                result = MAPPER.writeValueAsBytes(object);
            }
        } catch (Exception ignored) {
            // 非法 JSON 交给后续转换器处理
        }
        byte[] finalBody = result;
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(inputMessage.getHeaders());
        headers.setContentLength(finalBody.length);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(finalBody);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
