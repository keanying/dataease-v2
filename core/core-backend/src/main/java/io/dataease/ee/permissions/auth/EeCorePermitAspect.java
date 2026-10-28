package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.dataset.dto.EnumObj;
import io.dataease.api.dataset.dto.EnumValueRequest;
import io.dataease.api.dataset.dto.MultFieldValuesRequest;
import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.api.ds.vo.BusiCreateFolderRequest;
import io.dataease.api.ds.vo.BusiDsRequest;
import io.dataease.api.ds.vo.BusiRenameRequest;
import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.BusiResourceEnum;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.dao.auto.entity.QCoreChartView;
import io.dataease.dao.auto.entity.QCoreDatasetTableField;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.exception.DEException;
import io.dataease.extensions.datasource.dto.DatasourceDTO;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 补充核心接口中缺少 {@code @DePermit} 的资源鉴权：数据源增改移、数据集详情与取值、图表读写。
 */
@Aspect
@Component
@Order(6)
public class EeCorePermitAspect {

    private final EePermissionManage permissionManage;

    private final EeBusiResourceManage busiResourceManage;

    private final CorePermissionExtension corePermission;

    private final JPAQueryFactory queryFactory;

    public EeCorePermitAspect(EePermissionManage permissionManage, EeBusiResourceManage busiResourceManage,
                              CorePermissionExtension corePermission, JPAQueryFactory queryFactory) {
        this.permissionManage = permissionManage;
        this.busiResourceManage = busiResourceManage;
        this.corePermission = corePermission;
        this.queryFactory = queryFactory;
    }

    // ------------------------------------------------------------------ helpers

    private boolean bypass() {
        Long uid = V3UserUtil.getUid();
        return uid == null || V3UserUtil.isSysAdmin(uid);
    }

    private void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }

    private void require(int rtId, Long id, int level) {
        if (bypass()) {
            return;
        }
        Long target = id == null || id < 0 ? 0L : id;
        if (!permissionManage.current(rtId, target).satisfies(level)) {
            deny();
        }
    }

    /**
     * 能否新建该类资源：可在根目录创建，或对任一目录有管理权限
     */
    private void requireCreate(int rtId) {
        if (bypass()) {
            return;
        }
        Long uid = V3UserUtil.getUid();
        Long oid = V3UserUtil.getOid();
        if (permissionManage.canManageRoot(uid, oid, rtId)) {
            return;
        }
        boolean anyManage = permissionManage.effective(uid, oid, rtId).values().stream().anyMatch(perm -> perm.satisfies(EeConstants.WEIGHT_MANAGE));
        if (!anyManage) {
            deny();
        }
    }

    private void requireDatasetRead(Long datasetId) {
        if (bypass() || datasetId == null) {
            return;
        }
        if (!corePermission.checkAuth(new BusiPerCheckDTO(datasetId, BusiResourceEnum.DATASET, AuthEnum.READ))) {
            deny();
        }
    }

    private void requireFieldsRead(Collection<Long> fieldIds) {
        if (bypass() || CollectionUtils.isEmpty(fieldIds)) {
            return;
        }
        QCoreDatasetTableField q = QCoreDatasetTableField.coreDatasetTableField;
        List<Long> datasetIds = queryFactory.select(q.datasetGroupId).distinct().from(q).where(q.id.in(fieldIds)).fetch();
        datasetIds.stream().filter(Objects::nonNull).forEach(this::requireDatasetRead);
    }

    private void requireScene(Long sceneId, int level) {
        if (bypass() || sceneId == null) {
            return;
        }
        PerBusiResource resource = busiResourceManage.get(sceneId);
        if (resource == null) {
            return;
        }
        if (!permissionManage.current(resource.getRtId(), sceneId).satisfies(level)) {
            deny();
        }
    }

    private void requireChart(Long chartId, int level) {
        if (bypass() || chartId == null) {
            return;
        }
        QCoreChartView q = QCoreChartView.coreChartView;
        Long sceneId = queryFactory.select(q.sceneId).from(q).where(q.id.eq(chartId)).fetchFirst();
        requireScene(sceneId, level);
    }

    // ------------------------------------------------------------------ datasource

    /**
     * 通用参数查询接口不返回密码、密钥类设置项（邮件密码、HMAC 密钥等仅由对应的设置接口按权限返回）
     */
    @Around("execution(* io.dataease.api.system.SysParameterApi.singleVal(..)) && args(key)")
    public Object sysParameterSingleVal(ProceedingJoinPoint joinPoint, String key) throws Throwable {
        if (key != null && SECRET_KEY.matcher(key).find()) {
            return null;
        }
        return joinPoint.proceed();
    }

    private static final java.util.regex.Pattern SECRET_KEY = java.util.regex.Pattern.compile("(?i)(pwd|password|secret|token)");

    @Before("execution(* io.dataease.api.ds.DatasourceApi.save(..)) && args(request)")
    public void dsSave(BusiDsRequest request) {
        if (request.getId() == null) {
            require(EeConstants.RT_DATASOURCE, request.getPid(), EeConstants.WEIGHT_MANAGE);
        } else {
            require(EeConstants.RT_DATASOURCE, request.getId(), EeConstants.WEIGHT_MANAGE);
        }
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.update(..)) && args(request)")
    public void dsUpdate(BusiDsRequest request) {
        require(EeConstants.RT_DATASOURCE, request.getId(), EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.move(..)) && args(request)")
    public void dsMove(BusiDsRequest request) {
        require(EeConstants.RT_DATASOURCE, request.getId(), EeConstants.WEIGHT_MANAGE);
        require(EeConstants.RT_DATASOURCE, request.getPid(), EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.reName(..)) && args(request)")
    public void dsRename(BusiRenameRequest request) {
        require(EeConstants.RT_DATASOURCE, request.getId(), EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.createFolder(..)) && args(request)")
    public void dsCreateFolder(BusiCreateFolderRequest request) {
        require(EeConstants.RT_DATASOURCE, request.getPid(), EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.getSchema(..)) && args(request)")
    public void dsSchema(BusiDsRequest request) {
        if (request.getId() != null) {
            require(EeConstants.RT_DATASOURCE, request.getId(), EeConstants.WEIGHT_MANAGE);
        } else {
            requireCreate(EeConstants.RT_DATASOURCE);
        }
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.checkApiDatasource(..)) || execution(* io.dataease.api.ds.DatasourceApi.loadRemoteFile(..))"
            + " || execution(* io.dataease.api.ds.DatasourceApi.multidimensionalTables(..))")
    public void dsCreateHelpers() {
        requireCreate(EeConstants.RT_DATASOURCE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.uploadFile(..)) && args(file, datasourceId, editType)")
    public void dsUpload(Object file, long datasourceId, Integer editType) {
        if (datasourceId > 0) {
            require(EeConstants.RT_DATASOURCE, datasourceId, EeConstants.WEIGHT_MANAGE);
        } else {
            requireCreate(EeConstants.RT_DATASOURCE);
        }
    }

    @Before("(execution(* io.dataease.api.ds.DatasourceApi.syncApiTable(..)) || execution(* io.dataease.api.ds.DatasourceApi.syncApiDs(..))) && args(req)")
    public void dsSyncApi(Map<String, String> req) {
        long id = NumberUtils.toLong(req == null ? null : req.get("datasourceId"), 0L);
        require(EeConstants.RT_DATASOURCE, id, EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.listSyncRecord(..)) && args(goPage, pageSize, dsId)")
    public void dsSyncRecord(int goPage, int pageSize, Long dsId) {
        require(EeConstants.RT_DATASOURCE, dsId, EeConstants.WEIGHT_READ);
    }

    @Before("execution(* io.dataease.api.ds.DatasourceApi.simple(..)) && args(id)")
    public void dsSimple(Long id) {
        require(EeConstants.RT_DATASOURCE, id, EeConstants.WEIGHT_READ);
    }

    @Around("execution(* io.dataease.api.ds.DatasourceApi.query(..))")
    @SuppressWarnings("unchecked")
    public Object dsQuery(ProceedingJoinPoint point) throws Throwable {
        Object result = point.proceed();
        if (bypass() || !(result instanceof List<?> list)) {
            return result;
        }
        Map<Long, Perm> perms = permissionManage.effective(V3UserUtil.getUid(), V3UserUtil.getOid(), EeConstants.RT_DATASOURCE);
        List<Object> filtered = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof DatasourceDTO dto && (dto.getId() == null || !perms.containsKey(dto.getId()))) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    // ------------------------------------------------------------------ dataset

    @Before("(execution(* io.dataease.api.dataset.DatasetTreeApi.get(..)) || execution(* io.dataease.api.dataset.DatasetTreeApi.details(..))"
            + " || execution(* io.dataease.api.dataset.DatasetTreeApi.barInfo(..))) && args(id)")
    public void datasetDetail(Long id) {
        requireDatasetRead(id);
    }

    @Before("(execution(* io.dataease.api.dataset.DatasetTreeApi.panelGetDsDetails(..)) || execution(* io.dataease.api.dataset.DatasetTreeApi.getSqlParams(..))"
            + " || execution(* io.dataease.api.dataset.DatasetTreeApi.detailWithPerm(..))) && args(ids)")
    public void datasetDetails(List<Long> ids) {
        if (ids != null) {
            ids.forEach(this::requireDatasetRead);
        }
    }

    @Before("(execution(* io.dataease.api.dataset.DatasetDataApi.previewData(..)) || execution(* io.dataease.api.dataset.DatasetDataApi.getDatasetCount(..))"
            + " || execution(* io.dataease.api.dataset.DatasetDataApi.getDatasetTotal(..))) && args(dto)")
    public void datasetPreview(DatasetGroupInfoDTO dto) {
        if (dto != null && dto.getId() != null) {
            requireDatasetRead(dto.getId());
        }
    }

    @Before("(execution(* io.dataease.api.dataset.DatasetDataApi.getFieldEnum(..)) || execution(* io.dataease.api.dataset.DatasetDataApi.getFieldValueTree(..))) && args(request)")
    public void fieldEnum(MultFieldValuesRequest request) {
        if (request != null) {
            requireFieldsRead(request.getFieldIds());
        }
    }

    @Before("execution(* io.dataease.api.dataset.DatasetDataApi.getFieldEnumObj(..)) && args(request)")
    public void fieldEnumObj(EnumValueRequest request) {
        if (request != null) {
            List<Long> ids = new ArrayList<>();
            Optional.ofNullable(request.getQueryId()).ifPresent(ids::add);
            Optional.ofNullable(request.getDisplayId()).ifPresent(ids::add);
            Optional.ofNullable(request.getSortId()).ifPresent(ids::add);
            requireFieldsRead(ids);
        }
    }

    @Before("execution(* io.dataease.api.dataset.DatasetDataApi.getFieldEnumDs(..)) && args(request)")
    public void fieldEnumDs(EnumObj request) {
        if (request != null && request.getDataset() != null && request.getDataset().getId() != null) {
            requireDatasetRead(request.getDataset().getId());
        }
    }

    // ------------------------------------------------------------------ chart

    @Before("execution(* io.dataease.api.chart.ChartViewApi.getData(..)) && args(id)")
    public void chartGet(Long id) {
        requireChart(id, EeConstants.WEIGHT_READ);
    }

    @Before("execution(* io.dataease.api.chart.ChartViewApi.getDetail(..)) && args(id, resourceTable)")
    public void chartDetail(Long id, String resourceTable) {
        requireChart(id, EeConstants.WEIGHT_READ);
    }

    @Before("execution(* io.dataease.api.chart.ChartViewApi.save(..)) && args(dto)")
    public void chartSave(ChartViewDTO dto) {
        if (dto != null) {
            requireScene(dto.getSceneId(), EeConstants.WEIGHT_MANAGE);
        }
    }

    @Before("execution(* io.dataease.api.chart.ChartViewApi.viewOption(..)) && args(resourceId)")
    public void chartViewOption(Long resourceId) {
        requireScene(resourceId, EeConstants.WEIGHT_READ);
    }

    @Before("execution(* io.dataease.api.chart.ChartViewApi.copyField(..)) && args(id, chartId)")
    public void chartCopyField(Long id, Long chartId) {
        requireChart(chartId, EeConstants.WEIGHT_MANAGE);
    }

    @Before("execution(* io.dataease.api.chart.ChartViewApi.deleteFieldByChart(..)) && args(chartId)")
    public void chartDeleteFields(Long chartId) {
        requireChart(chartId, EeConstants.WEIGHT_MANAGE);
    }
}
