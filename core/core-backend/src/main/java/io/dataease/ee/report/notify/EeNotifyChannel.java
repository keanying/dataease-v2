package io.dataease.ee.report.notify;

/**
 * 消息发送渠道 SPI（定时报告、阈值告警共用）。
 *
 * <p>实现类注册为 Spring Bean 即可被 {@link EeNotifyDispatcher} 自动发现：</p>
 * <ul>
 *     <li>{@link #type()} 返回 {@link EeNotifyTypes} 中的常量，同一类型只能有一个 Bean；</li>
 *     <li>{@link #send(EeNotifyMessage)} 同步发送，失败时抛出异常（异常信息会写入任务日志），
 *     {@link EeNotifyMessage#getTargets()} 的含义随渠道不同，见 {@link EeNotifyTypes}；</li>
 *     <li>调用时 {@code V3UserUtil} 已设置为任务创建人身份，组织为任务所属组织。</li>
 * </ul>
 *
 * <p>本模块只实现了邮件渠道 {@link EeEmailChannel}；企业微信、钉钉、飞书、Lark 及其群机器人、Webhook
 * 渠道由对应模块实现本接口后自动生效，未实现的渠道发送时记录“渠道不可用”。</p>
 */
public interface EeNotifyChannel {

    /**
     * 渠道类型，取值见 {@link EeNotifyTypes}
     */
    String type();

    /**
     * 发送消息
     *
     * @param msg 消息；targets 已去重且非空
     * @throws Exception 发送失败
     */
    void send(EeNotifyMessage msg) throws Exception;
}
