package io.dataease.ee.report.notify;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * 把 HTML 正文转换为 IM / Webhook 使用的纯文本。
 */
public final class EeNotifyText {

    private EeNotifyText() {
    }

    public static String plain(String html) {
        if (StringUtils.isBlank(html)) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n")
                .replaceAll("(?s)<[^>]+>", "");
        text = HtmlUtils.htmlUnescape(text);
        return text.replaceAll("[ \\t\\x0B\\f\\r]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }
}
