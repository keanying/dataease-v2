package io.dataease.ee.report.notify;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 接收人配置（报告与告警共用）。
 */
@Getter
@Setter
public class EeRecipients {

    /**
     * 个人渠道（1 邮件 2 企业微信 3 钉钉 4 飞书 5 国际飞书），作用于 uids/rids 解析出的用户
     */
    private List<Integer> reciFlags = new ArrayList<>();

    private List<Long> uids = new ArrayList<>();

    private List<Long> rids = new ArrayList<>();

    /**
     * 额外邮箱（始终走邮件渠道）
     */
    private List<String> emails = new ArrayList<>();

    private List<String> dingtalkGroups = new ArrayList<>();

    private List<String> larkGroups = new ArrayList<>();

    private List<String> larksuiteGroups = new ArrayList<>();

    private List<String> webhooks = new ArrayList<>();
}
