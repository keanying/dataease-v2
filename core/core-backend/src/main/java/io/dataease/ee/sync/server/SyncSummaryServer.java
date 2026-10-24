package io.dataease.ee.sync.server;

import io.dataease.api.sync.summary.api.SummaryApi;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.manage.SyncAccess;
import io.dataease.ee.sync.manage.SyncSummaryManage;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 同步管理：概览
 */
@RestController("syncSummaryServer")
@RequestMapping("/sync/summary")
public class SyncSummaryServer implements SummaryApi {

    private final SyncSummaryManage manage;
    private final SyncAccess access;

    public SyncSummaryServer(SyncSummaryManage manage, SyncAccess access) {
        this.manage = manage;
        this.access = access;
    }

    private Long scope() {
        access.check(SyncConstants.MENU_SUMMARY);
        return access.oid();
    }

    @Override
    public Map<String, Long> resourceCount() {
        return manage.resourceCount(scope());
    }

    /**
     * 前端以空请求体调用，参数标记为非必填（覆盖接口上的 @RequestBody）
     */
    @Override
    public Map<String, Object> logChartData(@RequestBody(required = false) String executeTaskLogDate) {
        return manage.logChartData(scope());
    }
}
