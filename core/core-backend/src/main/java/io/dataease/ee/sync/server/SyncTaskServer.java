package io.dataease.ee.sync.server;

import io.dataease.api.sync.task.api.TaskApi;
import io.dataease.api.sync.task.dto.TaskGridRequest;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.manage.SyncAccess;
import io.dataease.ee.sync.manage.SyncDatasourceManage;
import io.dataease.ee.sync.manage.SyncTaskManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.task.Target;
import io.dataease.extensions.sync.model.task.TaskInfoVO;
import io.dataease.extensions.sync.provider.SinkKeyPolicy;
import io.dataease.result.PageResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 同步管理：任务
 */
@RestController("syncTaskServer")
@RequestMapping("/sync/task")
public class SyncTaskServer implements TaskApi {

    private final SyncTaskManage manage;
    private final SyncAccess access;

    public SyncTaskServer(SyncTaskManage manage, SyncAccess access) {
        this.manage = manage;
        this.access = access;
    }

    private Long scope() {
        access.check(SyncConstants.MENU_TASK);
        return access.oid();
    }

    @Override
    public PageResult<TaskInfoVO> pager(int goPage, int pageSize, TaskGridRequest request) {
        return manage.pager(scope(), goPage, pageSize, request);
    }

    @Override
    public void add(TaskInfoVO jobInfo) throws DEException {
        manage.add(scope(), jobInfo);
    }

    @Override
    public void update(TaskInfoVO jobInfo) throws DEException {
        manage.update(scope(), jobInfo);
    }

    @Override
    public void remove(String id) throws DEException {
        manage.remove(scope(), id);
    }

    @Override
    public void startJob(String id) throws DEException {
        manage.start(scope(), id);
    }

    @Override
    public void stopJob(String id) throws DEException {
        manage.stop(scope(), id);
    }

    @Override
    public TaskInfoVO getOneById(String id) throws DEException {
        return manage.get(scope(), id);
    }

    @Override
    public SinkKeyPolicy targetKeyPolicy(Target target) throws DEException {
        return manage.keyPolicy(scope(), target);
    }

    @Override
    public void execute(String id) throws DEException {
        manage.execute(scope(), id);
    }

    @Override
    public void batchDelete(List<String> ids) throws DEException {
        manage.batchRemove(scope(), ids);
    }

    @Override
    public Long count() throws DEException {
        return manage.count(scope());
    }

    @Override
    public String query2Root(Long id) {
        scope();
        return null;
    }

    @Override
    public Long getLogResourceId(String id) {
        scope();
        return SyncDatasourceManage.parseId(id);
    }
}
