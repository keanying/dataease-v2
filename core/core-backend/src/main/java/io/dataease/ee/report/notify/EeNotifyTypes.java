package io.dataease.ee.report.notify;

/**
 * 消息渠道类型，以及各渠道下 {@link EeNotifyMessage#getTargets()} 的含义。
 */
public final class EeNotifyTypes {

    private EeNotifyTypes() {
    }

    /**
     * 邮件；targets = 邮箱地址
     */
    public static final String EMAIL = "email";

    /**
     * 企业微信个人消息；targets = 企业微信 userid（per_user_platform.platform_uid，origin=6），uids = 对应 DataEase 用户
     */
    public static final String WECOM = "wecom";

    /**
     * 钉钉个人消息；targets = 钉钉 userid（origin=5）
     */
    public static final String DINGTALK = "dingtalk";

    /**
     * 飞书个人消息；targets = 飞书用户标识（origin=4）
     */
    public static final String LARK = "lark";

    /**
     * 国际飞书（Lark）个人消息；targets = Lark 用户标识（origin=7）
     */
    public static final String LARKSUITE = "larksuite";

    /**
     * 钉钉群；targets = 群 chat_id（/dingtalk/getGroup 返回的 chat_id）
     */
    public static final String DINGTALK_GROUP = "dingtalk_group";

    /**
     * 飞书群；targets = 群 chat_id
     */
    public static final String LARK_GROUP = "lark_group";

    /**
     * 国际飞书群；targets = 群 chat_id
     */
    public static final String LARKSUITE_GROUP = "larksuite_group";

    /**
     * Webhook；targets = xpack_webhook 的 ID（字符串）
     */
    public static final String WEBHOOK = "webhook";

    /**
     * 前端 reciFlagList 取值（1 邮件 2 企业微信 3 钉钉 4 飞书 5 国际飞书）对应的个人渠道
     */
    public static String ofReciFlag(Integer flag) {
        if (flag == null) {
            return null;
        }
        return switch (flag) {
            case 1 -> EMAIL;
            case 2 -> WECOM;
            case 3 -> DINGTALK;
            case 4 -> LARK;
            case 5 -> LARKSUITE;
            default -> null;
        };
    }

    /**
     * 个人渠道对应的用户来源（per_user_platform.origin）
     */
    public static Integer platformOrigin(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case WECOM -> 6;
            case DINGTALK -> 5;
            case LARK -> 4;
            case LARKSUITE -> 7;
            default -> null;
        };
    }
}
