package io.dataease.ee.platform.message;

import java.util.Collection;

/**
 * 平台消息发送器（每个平台一个 Bean），供定时报告、告警等模块调用。
 *
 * <p>通常通过 {@link EePlatformMessageService} 按 DataEase 用户 ID 发送；需要直接按平台用户 ID / 群 ID 发送时可注入
 * {@code List<EePlatformMessageSender>} 按 {@link #origin()} 选择。发送失败抛出异常（含平台返回的错误信息）。</p>
 */
public interface EePlatformMessageSender {

    /**
     * 平台来源编码：4 飞书、5 钉钉、6 企业微信、7 国际飞书
     */
    int origin();

    /**
     * 平台已配置、启用且校验通过
     */
    boolean available();

    /**
     * 发送给平台用户（钉钉 userid、飞书 user_id / open_id、企业微信 userid）
     */
    void sendToUsers(Collection<String> platformUserIds, EePlatformMessage message);

    /**
     * 发送到群（钉钉 openConversationId、飞书 chat_id、企业微信应用群 chatid）
     */
    void sendToGroups(Collection<String> groupIds, EePlatformMessage message);
}
