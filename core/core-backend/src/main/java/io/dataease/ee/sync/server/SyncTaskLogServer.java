package io.dataease.ee.sync.server;

import io.dataease.api.sync.task.api.TaskLogApi;
import io.dataease.api.sync.task.dto.TaskLogGridRequest;
import io.dataease.api.sync.task.vo.LogResultVO;
import io.dataease.api.sync.task.vo.TaskLogVO;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncTaskLog;
import io.dataease.ee.sync.engine.SyncTaskRunner;
import io.dataease.ee.sync.manage.SyncAccess;
import io.dataease.ee.sync.manage.SyncDatasourceManage;
import io.dataease.ee.sync.manage.SyncTaskLogManage;
import io.dataease.ee.sync.manage.SyncTaskManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.result.PageResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 同步管理：任务日志
 */
@RestController("syncTaskLogServer")
@RequestMapping("/sync/task/log")
public class SyncTaskLogServer implements TaskLogApi {

    private final SyncTaskLogManage manage;
    private final SyncTaskManage taskManage;
    private final SyncTaskRunner runner;
    private final SyncAccess access;

    public SyncTaskLogServer(SyncTaskLogManage manage, SyncTaskManage taskManage, SyncTaskRunner runner, SyncAccess access) {
        this.manage = manage;
        this.taskManage = taskManage;
        this.runner = runner;
        this.access = access;
    }

    private Long scope() {
        access.check(SyncConstants.MENU_TASK);
        return access.oid();
    }

    @Override
    public PageResult<TaskLogVO> pager(int goPage, int pageSize, TaskLogGridRequest request) {
        return manage.pager(scope(), goPage, pageSize, request);
    }

    @Override
    public LogResultVO logDetail(String logId, int fromLineNum) {
        return manage.detail(scope(), logId, fromLineNum);
    }

    /**
     * 日志由执行引擎写入，不开放外部写入
     */
    @Override
    public void saveLog(TaskLogVO logDetail) {
        scope();
        DEException.throwException(Translator.get("ee_sync_op_unsupported"));
    }

    @Override
    public void updateLog(TaskLogVO logDetail) {
        scope();
        DEException.throwException(Translator.get("ee_sync_op_unsupported"));
    }

    @Override
    public void deleteByJobId(String jobId) {
        Long oid = scope();
        Long taskId = taskManage.require(oid, jobId).getId();
        if (runner.isRunning(taskId)) {
            DEException.throwException(Translator.get("ee_sync_task_running"));
        }
        manage.deleteByTask(taskId);
    }

    @Override
    public void deleteById(String logId) {
        manage.delete(scope(), logId);
    }

    @Override
    public void clearJobLog(TaskLogVO taskLogVO) {
        Long oid = scope();
        Long taskId = null;
        if (taskLogVO != null && StringUtils.isNotBlank(taskLogVO.getJobId())) {
            taskId = taskManage.require(oid, taskLogVO.getJobId()).getId();
        }
        manage.clear(oid, taskId, taskLogVO == null ? null : taskLogVO.getClearType());
    }

    @Override
    public void terminationTask(String logId) {
        Long oid = scope();
        PerSyncTaskLog log = manage.require(oid, logId);
        if (!SyncConstants.LOG_RUNNING.equals(log.getStatus())) {
            return;
        }
        if (!runner.terminate(log.getId()) && !manage.isLive(log.getId())) {
            // 执行节点已不存在（如服务重启），直接标记为终止
            manage.finish(log.getId(), SyncConstants.LOG_TERMINATION, Translator.get("ee_sync_log_terminated"), 0);
            runner.finishTask(log.getTaskId(), log.getId(), SyncConstants.LOG_TERMINATION, false);
        }
    }

    @Override
    public Long getLogResourceId(Map<String, String> params) {
        scope();
        return params == null ? null : SyncDatasourceManage.parseId(params.get("id"));
    }

    @Override
    public String query2Root(Map<String, String> params) {
        scope();
        return null;
    }
}
