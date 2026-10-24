package io.dataease.ee.message;

/**
 * 消息中心未读/待办数量提供者。
 *
 * <p>页头消息角标（{@code POST /msg-center/count}）为全部提供者返回值之和。各企业版模块（如数据填报的待填报任务）
 * 实现该接口并注册为 Bean 即可，没有提供者时角标为 0。实现应快速返回，异常会被忽略（按 0 计）。</p>
 */
public interface EeMsgCountProvider {

    /**
     * 用户在组织内的未读/待办数量
     *
     * @param uid 当前用户
     * @param oid 当前组织（可能为空）
     */
    long count(Long uid, Long oid);
}
