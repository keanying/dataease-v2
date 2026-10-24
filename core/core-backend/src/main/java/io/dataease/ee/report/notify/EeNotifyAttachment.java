package io.dataease.ee.report.notify;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 消息附件（截图、PDF、Excel）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EeNotifyAttachment {

    /**
     * 文件名
     */
    private String name;

    /**
     * MIME 类型
     */
    private String contentType;

    /**
     * 文件内容
     */
    private byte[] data;

    /**
     * 是否为正文内嵌图片（邮件中以 cid 引用，IM 渠道可直接作为图片消息发送）
     */
    private boolean inline;

    /**
     * 内嵌图片的 Content-ID（正文中以 {@code cid:<contentId>} 引用）
     */
    private String contentId;
}
