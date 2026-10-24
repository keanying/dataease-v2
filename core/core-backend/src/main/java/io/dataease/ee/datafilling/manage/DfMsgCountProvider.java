package io.dataease.ee.datafilling.manage;

import io.dataease.ee.message.EeMsgCountProvider;
import org.springframework.stereotype.Component;

/**
 * 消息中心角标：待填报任务数
 */
@Component
public class DfMsgCountProvider implements EeMsgCountProvider {

    private final DfTaskManage taskManage;

    public DfMsgCountProvider(DfTaskManage taskManage) {
        this.taskManage = taskManage;
    }

    @Override
    public long count(Long uid, Long oid) {
        return taskManage.countTodo(uid, oid);
    }
}
