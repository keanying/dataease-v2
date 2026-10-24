package io.dataease.ee.report.notify;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 待发送的消息。
 */
@Getter
@Setter
public class EeNotifyMessage {

    /**
     * 渠道类型，见 {@link EeNotifyTypes}
     */
    private String type;

    /**
     * 接收目标，含义随渠道不同，见 {@link EeNotifyTypes}
     */
    private List<String> targets = new ArrayList<>();

    /**
     * 个人渠道对应的 DataEase 用户 ID（可能为空）
     */
    private List<Long> uids = new ArrayList<>();

    /**
     * 标题
     */
    private String title;

    /**
     * 正文（HTML）
     */
    private String content;

    /**
     * 附件；{@link EeNotifyAttachment#isInline()} 为 true 的图片应内嵌在正文中展示
     */
    private List<EeNotifyAttachment> attachments = new ArrayList<>();

    /**
     * 来源：report / threshold
     */
    private String source;

    /**
     * 来源任务 ID（报告任务或告警 ID）
     */
    private Long sourceId;

    /**
     * 所属组织
     */
    private Long oid;

    public EeNotifyMessage copyFor(String type, List<String> targets, List<Long> uids) {
        EeNotifyMessage msg = new EeNotifyMessage();
        msg.setType(type);
        msg.setTargets(targets == null ? new ArrayList<>() : new ArrayList<>(targets));
        msg.setUids(uids == null ? new ArrayList<>() : new ArrayList<>(uids));
        msg.setTitle(title);
        msg.setContent(content);
        msg.setAttachments(attachments);
        msg.setSource(source);
        msg.setSourceId(sourceId);
        msg.setOid(oid);
        return msg;
    }
}
