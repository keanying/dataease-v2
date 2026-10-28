package io.dataease.ee.message;

import io.dataease.api.communicate.dto.MessageDTO;
import io.dataease.constant.MessageEnum;

/**
 * 消息发送渠道扩展点，{@code CommunicateApi.send} 按 {@link MessageDTO#getMessageEnum()} 分发。
 *
 * <p>内置：WEBHOOK（{@code recipientList} 为 webhook ID）、INNER（站内消息，由 {@link EeMsgCountProvider}
 * 统计，发送为空操作），邮件、企业微信、钉钉、飞书、国际飞书及其群消息由 {@link EeNotifyMessageChannel}
 * 委托给定时报告共用的消息渠道（{@code io.dataease.ee.report.notify}）。</p>
 */
public interface EeMessageChannel {

    boolean supports(MessageEnum messageEnum);

    /**
     * 发送；失败时抛出 {@link io.dataease.exception.DEException}
     */
    void send(MessageDTO message);
}
