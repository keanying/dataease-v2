package io.dataease.ee.report.notify;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次分发的结果汇总。
 */
@Getter
public class EeNotifyResult {

    /**
     * 成功发送的渠道数
     */
    private int successCount;

    /**
     * 失败信息（渠道级）
     */
    private final List<String> errors = new ArrayList<>();

    /**
     * 提示信息（如部分用户未绑定平台账号）
     */
    private final List<String> warnings = new ArrayList<>();

    void success() {
        successCount++;
    }

    void error(String msg) {
        errors.add(msg);
    }

    void warn(String msg) {
        warnings.add(msg);
    }

    public boolean isSuccess() {
        return errors.isEmpty() && successCount > 0;
    }

    public String summary() {
        List<String> lines = new ArrayList<>(errors);
        lines.addAll(warnings);
        return String.join("\n", lines);
    }
}
