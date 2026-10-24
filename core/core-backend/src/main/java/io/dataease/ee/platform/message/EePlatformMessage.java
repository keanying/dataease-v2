package io.dataease.ee.platform.message;

import lombok.Getter;

/**
 * 发送到钉钉 / 飞书 / 国际飞书 / 企业微信的消息
 */
@Getter
public class EePlatformMessage {

    public enum Type {
        /**
         * 纯文本
         */
        TEXT,
        /**
         * Markdown（企业微信应用消息、钉钉、飞书卡片均支持基础语法）
         */
        MARKDOWN,
        /**
         * 图片（data 为图片字节）
         */
        IMAGE,
        /**
         * 文件（data 为文件字节）
         */
        FILE
    }

    private final Type type;

    private final String title;

    private final String content;

    private final byte[] data;

    private final String fileName;

    private EePlatformMessage(Type type, String title, String content, byte[] data, String fileName) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.data = data;
        this.fileName = fileName;
    }

    public static EePlatformMessage text(String content) {
        return new EePlatformMessage(Type.TEXT, null, content, null, null);
    }

    public static EePlatformMessage markdown(String title, String content) {
        return new EePlatformMessage(Type.MARKDOWN, title, content, null, null);
    }

    public static EePlatformMessage image(String fileName, byte[] data) {
        return new EePlatformMessage(Type.IMAGE, null, null, data, fileName);
    }

    public static EePlatformMessage file(String fileName, byte[] data) {
        return new EePlatformMessage(Type.FILE, null, null, data, fileName);
    }
}
