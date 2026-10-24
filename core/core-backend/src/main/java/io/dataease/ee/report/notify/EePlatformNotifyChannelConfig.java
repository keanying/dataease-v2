package io.dataease.ee.report.notify;

import io.dataease.ee.platform.message.EePlatformMessageSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册各平台的个人 / 群消息渠道。平台编码与 {@link EeNotifyTypes#platformOrigin(String)} 一致。
 */
@Configuration(proxyBeanMethods = false)
public class EePlatformNotifyChannelConfig {

    @Bean
    public EeNotifyChannel eeWecomNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.WECOM, 6, false, senders);
    }

    @Bean
    public EeNotifyChannel eeDingtalkNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.DINGTALK, 5, false, senders);
    }

    @Bean
    public EeNotifyChannel eeLarkNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.LARK, 4, false, senders);
    }

    @Bean
    public EeNotifyChannel eeLarksuiteNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.LARKSUITE, 7, false, senders);
    }

    @Bean
    public EeNotifyChannel eeDingtalkGroupNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.DINGTALK_GROUP, 5, true, senders);
    }

    @Bean
    public EeNotifyChannel eeLarkGroupNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.LARK_GROUP, 4, true, senders);
    }

    @Bean
    public EeNotifyChannel eeLarksuiteGroupNotifyChannel(ObjectProvider<EePlatformMessageSender> senders) {
        return new EePlatformNotifyChannel(EeNotifyTypes.LARKSUITE_GROUP, 7, true, senders);
    }
}
