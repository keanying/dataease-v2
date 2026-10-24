package io.dataease.ee.report;

import io.dataease.api.report.ReportApi;
import io.dataease.api.report.dto.*;
import io.dataease.api.report.vo.ReportGridVO;
import io.dataease.api.report.vo.ReportInfoVO;
import io.dataease.api.report.vo.ReportInstanceVO;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.result.PageResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时报告
 *
 * <p>任务按组织隔离；组织管理员可管理本组织全部任务，其他用户只能管理自己创建的任务。</p>
 */
@RestController("reportServer")
@RequestMapping("/report")
public class ReportServer implements ReportApi {

    private final ReportManage reportManage;

    public ReportServer(ReportManage reportManage) {
        this.reportManage = reportManage;
    }

    @Override
    public PageResult<ReportGridVO> pager(int goPage, int pageSize, ReportGridRequest request) {
        return reportManage.pager(goPage, pageSize, request);
    }

    @Override
    public void create(ReportCreator creator) {
        reportManage.create(creator);
    }

    @Override
    public void update(ReportEditor editor) {
        reportManage.update(editor);
    }

    @Override
    public void fireNow(Long taskId) {
        reportManage.fireNow(taskId);
    }

    @Override
    public void stopNow(Long taskId) {
        reportManage.stop(taskId);
    }

    @Override
    public void start(Long taskId) {
        reportManage.start(taskId);
    }

    @Override
    public void delete(List<Long> taskIdList) {
        reportManage.delete(taskIdList);
    }

    @Override
    public ReportInfoVO info(Long taskId) {
        return reportManage.info(taskId);
    }

    @Override
    public PageResult<ReportInstanceVO> pager(int goPage, int pageSize, ReportInstanceRequest request) {
        return reportManage.logPager(goPage, pageSize, request);
    }

    /**
     * 前端清理日志时周期放在 clearType 中（timeFlag 固定为 1），由 {@link ReportDeleteLogBodyAdvice} 映射到 timeFlag
     */
    @Override
    public void deleteInstance(ReportInstanceDelRequest request) {
        reportManage.deleteInstance(request);
    }

    @Override
    public String logMsg(ReportInstanceMsgRequest request) {
        return reportManage.logMsg(request);
    }

    @Override
    public ResponseEntity<ByteArrayResource> export(ReportExportRequest request) {
        DEException.throwException(Translator.get("ee_report_export_unsupported"));
        return null;
    }
}
