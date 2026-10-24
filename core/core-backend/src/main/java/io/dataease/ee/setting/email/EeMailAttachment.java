package io.dataease.ee.setting.email;

/**
 * 邮件附件。{@code contentId} 非空时作为内嵌资源（HTML 中以 {@code cid:<contentId>} 引用）。
 *
 * @param fileName    文件名
 * @param content     内容
 * @param contentType MIME 类型，为空时按文件名推断
 * @param contentId   内嵌资源 ID，可为空
 */
public record EeMailAttachment(String fileName, byte[] content, String contentType, String contentId) {

    public static EeMailAttachment of(String fileName, byte[] content) {
        return new EeMailAttachment(fileName, content, null, null);
    }

    public static EeMailAttachment inline(String contentId, byte[] content, String contentType) {
        return new EeMailAttachment(contentId, content, contentType, contentId);
    }
}
