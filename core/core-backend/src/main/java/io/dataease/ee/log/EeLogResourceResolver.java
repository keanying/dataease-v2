package io.dataease.ee.log;

/**
 * 操作日志资源名称解析扩展点。
 *
 * <p>其他企业版模块（数据填报、电子表格、定时报告、数据同步……）可以实现该接口并注册为 Bean，
 * 让 {@code @DeLog} 记录的日志展示资源名称/路径。返回 null 表示无法解析，继续使用内置解析。</p>
 */
public interface EeLogResourceResolver {

    /**
     * 是否支持该资源类型（{@link io.dataease.constant.LogST#getValue()}）
     */
    boolean supports(int sourceType);

    /**
     * 解析资源的展示文本（名称或以“/”分隔的路径）
     *
     * @param sourceType 资源类型
     * @param resourceId 资源ID（{@code @DeLog.id} 表达式的取值，可能是 Long 或 String）
     */
    String resolve(int sourceType, Object resourceId);
}
