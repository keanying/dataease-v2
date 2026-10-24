package io.dataease.ee.datafilling.server;

import io.dataease.api.export.BaseExportApi;
import io.dataease.api.report.dto.ReportInstanceMsgRequest;
import io.dataease.api.report.vo.ReportGridVO;
import io.dataease.api.xpack.dataFilling.DataFillingApi;
import io.dataease.api.xpack.dataFilling.dto.*;
import io.dataease.constant.LogOT;
import io.dataease.constant.LogST;
import io.dataease.constant.XpackSettingConstants;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.datafilling.manage.*;
import io.dataease.exception.DEException;
import io.dataease.exportCenter.manage.ExportCenterManage;
import io.dataease.extensions.datasource.dto.DatasetTableDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldRequest;
import io.dataease.extensions.datasource.dto.SimpleDatasourceDTO;
import io.dataease.extensions.datasource.dto.TableField;
import io.dataease.log.DeLog;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.BusiNodeVO;
import io.dataease.model.ResourceDeleteRequest;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.JsonUtil;
import io.dataease.utils.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据填报
 */
@RestController("dataFillingServer")
@RequestMapping("/data-filling")
public class DataFillingServer implements DataFillingApi {

    private final DfFormManage formManage;

    private final DfDataManage dataManage;

    private final DfExcelManage excelManage;

    private final DfTaskManage taskManage;

    private final DfAccess access;

    private final BaseExportApi exportApi;

    private final ExportCenterManage exportCenterManage;

    public DataFillingServer(DfFormManage formManage, DfDataManage dataManage, DfExcelManage excelManage, DfTaskManage taskManage,
                             DfAccess access, @Lazy BaseExportApi exportApi, @Lazy ExportCenterManage exportCenterManage) {
        this.formManage = formManage;
        this.dataManage = dataManage;
        this.excelManage = excelManage;
        this.taskManage = taskManage;
        this.access = access;
        this.exportApi = exportApi;
        this.exportCenterManage = exportCenterManage;
    }

    /**
     * 是否为数据填报接口自身的请求（导出中心等内部调用不做接口级鉴权）
     */
    private boolean fromApi() {
        try {
            HttpServletRequest request = ServletUtils.request();
            return request != null && StringUtils.contains(request.getRequestURI(), "/data-filling/");
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ tree & form

    @Override
    public List<BusiNodeVO> tree(BusiNodeRequest request) throws DEException {
        return formManage.tree(request);
    }

    @Override
    public DataFillingDTO get(Long id) {
        if (!fromApi()) {
            DataFillingDTO dto = formManage.findDTO(id);
            if (dto == null) {
                dto = new DataFillingDTO();
                dto.setId(id);
                dto.setName("");
            }
            return dto;
        }
        access.requireReadOrFiller(id);
        return formManage.get(id);
    }

    @Override
    @DeLog(id = "#p0.id", ot = LogOT.MODIFY, st = LogST.DATA_FILLING)
    public DataFillingDTO move(DataFillingDTO dataFillingDTO) {
        return formManage.move(dataFillingDTO);
    }

    @Override
    public DataFillingDTO save(DataFillingDTO dataFillingDTO) throws Exception {
        return formManage.save(dataFillingDTO);
    }

    @Override
    @DeLog(id = "#p0.id", ot = LogOT.MODIFY, st = LogST.DATA_FILLING)
    public DataFillingDTO update(DataFillingDTO dataFillingDTO) throws Exception {
        return formManage.update(dataFillingDTO);
    }

    @Override
    @DeLog(id = "#p0.id", ot = LogOT.MODIFY, st = LogST.DATA_FILLING)
    public DataFillingDTO rename(DataFillingDTO dataFillingDTO) {
        return formManage.rename(dataFillingDTO);
    }

    @Override
    @DeLog(id = "#p0.id", ot = LogOT.DELETE, st = LogST.DATA_FILLING)
    public void delete(ResourceDeleteRequest request) {
        formManage.delete(request);
    }

    @Override
    public List<SimpleDatasourceDTO> listDatasourceList() {
        return formManage.datasourceList(true);
    }

    @Override
    public List<SimpleDatasourceDTO> listDatasourceListAll() {
        return formManage.datasourceList(false);
    }

    @Override
    public List<DatasetTableDTO> getBuiltInTables() throws DEException {
        requireAnyManage();
        return formManage.builtInTables();
    }

    @Override
    public List<TableField> getBuiltInTableField(DatasetTableFieldRequest req) throws DEException {
        requireAnyManage();
        return formManage.builtInTableField(req == null ? null : req.getTableName());
    }

    /**
     * 可以在某处创建表单（根目录或任一目录有管理权限）
     */
    private void requireAnyManage() {
        if (V3UserUtil.isSysAdmin() || access.can(0L, EeConstants.WEIGHT_MANAGE)) {
            return;
        }
        List<BusiNodeVO> tree = formManage.tree(new BusiNodeRequest() {{
            setLeaf(false);
            setWeight(EeConstants.WEIGHT_MANAGE);
        }});
        if (tree.isEmpty() || tree.getFirst().getChildren() == null || tree.getFirst().getChildren().isEmpty()) {
            access.deny();
        }
    }

    // ------------------------------------------------------------------ options

    @Override
    public List<ColumnOption> listColumnData(Long formId, String fieldId) throws Exception {
        return dataManage.columnOptions(formId, fieldId);
    }

    @Override
    public List<ColumnOption> listColumnDataPreview(Long optionDatasource, DatasourceOptionsRequest request) throws Exception {
        return dataManage.optionsPreview(optionDatasource, request);
    }

    @Override
    public List<ExtraDetails> extraDetails(ExtraDetailsBaseRequest request) throws Exception {
        return dataManage.extraDetails(request);
    }

    @Override
    public List<ExtraDetails> extraDetailsPreview(ExtraDetailsRequest request) throws Exception {
        return dataManage.extraDetailsPreview(request);
    }

    // ------------------------------------------------------------------ table data

    @Override
    public DataFillFormTableDataResponse tableData(Long id, DataFillFormTableDataRequest request) throws Exception {
        return dataManage.tableData(id, request == null ? new DataFillFormTableDataRequest() : request);
    }

    @Override
    public void deleteRowData(Long formId, String id) throws Exception {
        dataManage.deleteRows(formId, List.of(id), null);
    }

    @Override
    @DeLog(id = "#p0", ot = LogOT.DELETE, st = LogST.DATA_FILLING)
    public void truncateRowData(Long formId) throws Exception {
        dataManage.truncate(formId);
    }

    @Override
    public void batchDeleteRowData(Long formId, List<String> ids) throws Exception {
        dataManage.deleteRows(formId, ids, null);
    }

    @Override
    public DataFillFormTableDataResponse saveRowData(Long formId, Map<String, Object> data) throws Exception {
        return dataManage.saveRow(formId, data, new DfDataManage.SaveOptions());
    }

    // ------------------------------------------------------------------ excel & export

    @Override
    public DfExcelData excelUpload(Long formId, MultipartFile file) throws Exception {
        if (!access.can(formId, EeConstants.WEIGHT_MANAGE) && !taskManage.hasActiveAppendTask(formId)) {
            access.deny();
        }
        return excelManage.upload(formId, file);
    }

    @Override
    public void excelTemplate(Long formId) {
        access.requireReadOrFiller(formId);
        excelManage.template(formId, ServletUtils.response());
    }

    @Override
    @DeLog(id = "#p0", ot = LogOT.UPLOADFILE, st = LogST.DATA_FILLING)
    public void confirmUpload(Long formId, Map<String, String> data) {
        excelManage.confirm(formId, data, null);
    }

    @Override
    public void appendDataConfirmUpload(Long id, Long formId, Map<String, String> data) {
        taskManage.appendConfirmUpload(id, formId, data);
    }

    @Override
    public void writeExcel(String file, DataFillFormTableDataRequest request, Long userId, Long org) throws Exception {
        excelManage.exportToFile(request.getId(), request, file);
    }

    @Override
    public void geFullName(Long pid, List<String> fullName) {
        formManage.fullName(pid, fullName);
    }

    @Override
    public void innerExport(Long formId, boolean isDataEaseBi, DataFillFormTableDataBaseRequest request, HttpServletResponse response) throws Exception {
        access.require(formId, EeConstants.WEIGHT_READ);
        DataFillingDTO form = formManage.get(formId);
        boolean sync = isDataEaseBi && !StringUtils.equalsIgnoreCase(exportCenterManage.singleValue(XpackSettingConstants.EMBEDDED_EXPORT_MODE), "async");
        if (sync) {
            DataFillFormTableDataRequest dataRequest = new DataFillFormTableDataRequest()
                    .setId(formId).setWithoutLogs(true).setSearchParams(request == null ? null : request.getSearchParams());
            excelManage.exportToResponse(formId, dataRequest, response);
            return;
        }
        HashMap<String, Object> params = new HashMap<>();
        params.put("name", form.getName());
        params.put("searchParams", JsonUtil.toJSONString(request == null || request.getSearchParams() == null ? List.of() : request.getSearchParams()).toString());
        params.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        Long proxy = V3UserUtil.getProxy().getProxyOid();
        if (proxy != null) {
            params.put("proxy", proxy);
        }
        exportApi.addTask(String.valueOf(formId), "data_filling", params, V3UserUtil.getUid(), V3UserUtil.getOid());
    }

    // ------------------------------------------------------------------ tasks

    @Override
    public TaskInfoVO info(Long taskId) {
        return taskManage.info(taskId);
    }

    @Override
    public Long saveTask(TaskInfoVO task) {
        return taskManage.saveTask(task);
    }

    @Override
    public void executeNow(TaskInfoVO task) {
        taskManage.executeNow(task);
    }

    @Override
    public String logMsg(ReportInstanceMsgRequest request) {
        return StringUtils.defaultString(taskManage.logMsg(request));
    }

    @Override
    public PageResult<ReportGridVO> taskPager(Long formId, int goPage, int pageSize, DfTaskInfoRequest request) {
        return taskManage.taskPage(formId, goPage, pageSize, request);
    }

    @Override
    public PageResult<DfSubTaskVo> subTaskPager(int goPage, int pageSize, DfSubTaskInfoRequest request) {
        return taskManage.subTaskPage(goPage, pageSize, request);
    }

    @Override
    public void batchDeleteTask(Long formId, List<Long> ids) throws Exception {
        taskManage.deleteTasks(formId, ids);
    }

    @Override
    public void stopTask(Long formId, Long id) throws Exception {
        taskManage.stop(formId, id);
    }

    @Override
    public void startTask(Long formId, Long id) throws Exception {
        taskManage.start(formId, id);
    }

    @Override
    public void batchDeleteSubTask(Long formId, List<Long> ids) throws Exception {
        taskManage.deleteSubTasks(formId, ids);
    }

    @Override
    public List<Map<String, Object>> listSubTaskUser(Long id, String type) throws Exception {
        return taskManage.subTaskUsers(id, type);
    }

    // ------------------------------------------------------------------ filler

    @Override
    public PageResult<DfUserTaskVo> listUserTask(int goPage, int pageSize, DfUserTaskRequest request) throws Exception {
        return taskManage.userTaskPage(goPage, pageSize, request);
    }

    @Override
    public long countUserTodoList() throws Exception {
        return taskManage.countCurrentUserTodo();
    }

    @Override
    public DfUserTaskData listUserTaskData(Long id) throws Exception {
        return taskManage.userTaskData(id);
    }

    @Override
    public DataFillFormTableDataResponse saveFormRowData(Long id, Map<String, Object> data) throws Exception {
        return taskManage.saveItemData(id, data);
    }

    @Override
    public DataFillFormTableDataResponse appendFormRowData(Long id, Map<String, Object> data) throws Exception {
        return taskManage.appendData(id, data);
    }

    @Override
    public void userTaskDeleteRowData(Long taskInstanceId, String id) throws Exception {
        taskManage.deleteUserTaskRow(taskInstanceId, id);
    }

    @Override
    public PageResult<DfCommitLog> logPager(DfCommitLogRequest request, int goPage, int pageSize) {
        return dataManage.logPage(request, goPage, pageSize);
    }

    @Override
    public void clearLog(DfClearCommitLogRequest request) throws Exception {
        dataManage.clearLog(request);
    }

    @Override
    public String getTemplateByUserTaskItemId(Long itemId) {
        return taskManage.template(itemId);
    }
}
