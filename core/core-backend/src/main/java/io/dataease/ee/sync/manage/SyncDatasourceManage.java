package io.dataease.ee.sync.manage;

import io.dataease.api.sync.datasource.dto.DBTableDTO;
import io.dataease.api.sync.datasource.dto.DatasourceGridRequest;
import io.dataease.ee.sync.SyncConstants;
import io.dataease.ee.sync.dao.PerSyncDatasource;
import io.dataease.ee.sync.dao.PerSyncDatasourceRepository;
import io.dataease.ee.sync.dao.PerSyncTask;
import io.dataease.ee.sync.dao.PerSyncTaskRepository;
import io.dataease.ee.sync.provider.SyncJdbcSupport;
import io.dataease.ee.sync.provider.SyncProviderResolver;
import io.dataease.exception.DEException;
import io.dataease.extensions.sync.model.SourceFieldMappingPolicy;
import io.dataease.extensions.sync.model.TableDTO;
import io.dataease.extensions.sync.model.TableFieldDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceDTO;
import io.dataease.extensions.sync.model.datasource.DatasourceRequest;
import io.dataease.extensions.sync.model.datasource.DatasourceRole;
import io.dataease.extensions.sync.provider.SinkProvider;
import io.dataease.extensions.sync.provider.SourceProvider;
import io.dataease.extensions.sync.provider.SyncProvider;
import io.dataease.i18n.Translator;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.utils.RsaUtils;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 同步数据源业务逻辑
 */
@Component
public class SyncDatasourceManage {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PerSyncDatasourceRepository datasourceRepository;
    private final PerSyncTaskRepository taskRepository;
    private final SyncProviderResolver resolver;

    /**
     * 后台状态检查（避免同一数据源并发检查）
     */
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();
    private final ExecutorService statusExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "de-sync-ds-status");
        t.setDaemon(true);
        return t;
    });

    public SyncDatasourceManage(PerSyncDatasourceRepository datasourceRepository, PerSyncTaskRepository taskRepository,
                                SyncProviderResolver resolver) {
        this.datasourceRepository = datasourceRepository;
        this.taskRepository = taskRepository;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<DatasourceRequest> pager(Long oid, int role, int goPage, int pageSize, DatasourceGridRequest request) {
        Specification<PerSyncDatasource> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("oid"), oid));
            predicates.add(cb.equal(root.get("datasourceRole"), role));
            if (request != null) {
                if (StringUtils.isNotBlank(request.getKeyword())) {
                    predicates.add(cb.like(cb.lower(root.get("name")), "%" + request.getKeyword().trim().toLowerCase() + "%"));
                }
                if (request.getDsType() != null && !request.getDsType().isEmpty()) {
                    predicates.add(root.get("type").in(request.getDsType()));
                }
                if (request.getStatus() != null && !request.getStatus().isEmpty()) {
                    predicates.add(root.get("status").in(request.getStatus()));
                }
                Long[] range = timeRange(request.getCreateTime());
                if (range != null) {
                    predicates.add(cb.between(root.get("createTime"), range[0], range[1]));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(Math.max(goPage, 1) - 1, Math.max(pageSize, 1), Sort.by(Sort.Direction.DESC, "createTime"));
        Page<PerSyncDatasource> page = datasourceRepository.findAll(spec, pageable);
        Map<Long, String> userNames = new HashMap<>();
        List<DatasourceRequest> records = page.getContent().stream().map(ds -> {
            DatasourceRequest vo = new DatasourceRequest();
            fill(vo, ds, userNames);
            return vo;
        }).toList();
        return new PageResult<>(records, page.getTotalElements(), pageable);
    }

    /**
     * 解析 ['yyyy-MM-dd HH:mm:ss','yyyy-MM-dd HH:mm:ss'] 或毫秒时间范围
     */
    public static Long[] timeRange(List<String> values) {
        if (values == null || values.size() < 2 || StringUtils.isAnyBlank(values.get(0), values.get(1))) {
            return null;
        }
        return new Long[]{parseTime(values.get(0)), parseTime(values.get(1))};
    }

    private static Long parseTime(String text) {
        String value = text.trim();
        if (value.matches("\\d{10,}")) {
            return Long.parseLong(value);
        }
        if (value.length() == 16) {
            value = value + ":00";
        } else if (value.length() == 10) {
            value = value + " 00:00:00";
        }
        return LocalDateTime.parse(value, TIME_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void fill(DatasourceDTO vo, PerSyncDatasource ds, Map<Long, String> userNames) {
        vo.setId(String.valueOf(ds.getId()));
        vo.setName(ds.getName());
        vo.setDesc(ds.getDescription());
        vo.setType(ds.getType());
        vo.setTypeName(resolver.typeName(ds.getType(), ds.getDatasourceRole()));
        vo.setStatus(ds.getStatus());
        vo.setStatusRemark(ds.getStatusRemark());
        vo.setCreateTime(ds.getCreateTime());
        vo.setUpdateTime(ds.getUpdateTime());
        vo.setCreateBy(ds.getCreateBy());
        vo.setDatasourceRole(ds.getDatasourceRole());
        if (ds.getCreateBy() != null && userNames != null) {
            vo.setCreateByName(userNames.computeIfAbsent(ds.getCreateBy(), uid -> {
                V3BaseUser user = V3UserUtil.getUser(uid);
                return user == null ? null : user.getName();
            }));
        }
    }

    public PerSyncDatasource require(Long oid, String id) {
        Long dsId = parseId(id);
        PerSyncDatasource ds = dsId == null ? null : datasourceRepository.findById(dsId).orElse(null);
        if (ds == null || !Objects.equals(ds.getOid(), oid)) {
            DEException.throwException(Translator.get("ee_sync_ds_not_found"));
        }
        return ds;
    }

    public static Long parseId(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public DatasourceDTO get(Long oid, String id) {
        PerSyncDatasource ds = require(oid, id);
        DatasourceDTO dto = new DatasourceDTO();
        fill(dto, ds, new HashMap<>());
        dto.setConfiguration(RsaUtils.symmetricEncrypt(ds.getConfiguration()));
        return dto;
    }

    public List<String> latestUse(Long oid, String sourceType) {
        int role = "target".equalsIgnoreCase(sourceType) ? DatasourceRole.TARGET : DatasourceRole.SOURCE;
        Specification<PerSyncDatasource> spec = (root, query, cb) -> cb.and(cb.equal(root.get("oid"), oid),
                cb.equal(root.get("datasourceRole"), role));
        Page<PerSyncDatasource> page = datasourceRepository.findAll(spec, PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createTime")));
        Set<String> types = new LinkedHashSet<>();
        for (PerSyncDatasource ds : page.getContent()) {
            types.add(ds.getType());
            if (types.size() >= 5) {
                break;
            }
        }
        return new ArrayList<>(types);
    }

    public List<DatasourceDTO> listByType(Long oid, String type, Integer role) {
        Specification<PerSyncDatasource> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("oid"), oid));
            predicates.add(cb.equal(root.get("type"), type));
            if (role != null) {
                predicates.add(cb.equal(root.get("datasourceRole"), role));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<PerSyncDatasource> list = datasourceRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createTime"));
        List<DatasourceDTO> result = new ArrayList<>();
        Map<Long, String> userNames = new HashMap<>();
        for (PerSyncDatasource ds : list) {
            DatasourceDTO dto = new DatasourceDTO();
            fill(dto, ds, userNames);
            if (Objects.equals(ds.getDatasourceRole(), DatasourceRole.TARGET)) {
                try {
                    SinkProvider sink = resolver.sink(resolver.provider(ds.getType(), ds.getDatasourceRole()));
                    DatasourceRequest req = internalRequest(ds);
                    dto.setUnsupportedKeyFieldTypes(sink.unsupportedKeyFieldTypes(req));
                    dto.setFieldIndexType(sink.fieldIndexType(req));
                    dto.setSupportedIndexFieldTypes(sink.supportedIndexFieldTypes(req));
                } catch (Exception e) {
                    LogUtil.warn("load sink capability failed: " + e.getMessage());
                }
            }
            result.add(dto);
        }
        return result;
    }

    public long count(Long oid) {
        return datasourceRepository.count((root, query, cb) -> cb.equal(root.get("oid"), oid));
    }

    // ------------------------------------------------------------------ 维护

    public void save(Long oid, DatasourceRequest request) {
        PerSyncDatasource ds = new PerSyncDatasource();
        ds.setId(IDUtils.snowID());
        apply(oid, ds, request, true);
        ds.setOid(oid);
        ds.setCreateBy(V3UserUtil.getUid());
        ds.setCreateTime(System.currentTimeMillis());
        ds.setUpdateBy(V3UserUtil.getUid());
        ds.setUpdateTime(ds.getCreateTime());
        datasourceRepository.save(ds);
    }

    public Map<String, Object> update(Long oid, DatasourceRequest request) {
        PerSyncDatasource ds = require(oid, request.getId());
        apply(oid, ds, request, false);
        ds.setUpdateBy(V3UserUtil.getUid());
        ds.setUpdateTime(System.currentTimeMillis());
        datasourceRepository.save(ds);
        boolean running = tasksUsing(ds.getId()).stream().anyMatch(t -> SyncConstants.TASK_RUNNING.equals(t.getStatus()));
        Map<String, Object> result = new HashMap<>();
        result.put("hasRunningTask", String.valueOf(running));
        return result;
    }

    private void apply(Long oid, PerSyncDatasource ds, DatasourceRequest request, boolean create) {
        String name = StringUtils.trimToEmpty(request.getName());
        if (name.length() < 1 || name.length() > 64) {
            DEException.throwException(Translator.get("ee_sync_ds_name_invalid"));
        }
        Integer role = create ? request.getDatasourceRole() : ds.getDatasourceRole();
        String type = create ? StringUtils.trimToEmpty(request.getType()) : ds.getType();
        if (create && !DatasourceRole.isValid(role)) {
            DEException.throwException(Translator.get("ee_sync_ds_type_invalid", type));
        }
        Specification<PerSyncDatasource> dup = (root, query, cb) -> cb.and(cb.equal(root.get("oid"), oid),
                cb.equal(root.get("datasourceRole"), role), cb.equal(root.get("name"), name));
        boolean duplicated = datasourceRepository.findAll(dup).stream().anyMatch(other -> !Objects.equals(other.getId(), ds.getId()));
        if (duplicated) {
            DEException.throwException(Translator.get("ee_sync_ds_name_duplicate"));
        }
        String configuration = decodeConfiguration(request.getConfiguration());
        SyncProvider provider = resolver.provider(type, role);
        DatasourceRequest probe = new DatasourceRequest();
        probe.setType(type);
        probe.setDatasourceRole(role);
        probe.setConfiguration(configuration);
        probe.setId(ds.getId() == null ? null : String.valueOf(ds.getId()));
        provider.checkConfiguration(probe);
        provider.configurationAdditionalParameters(probe);
        configuration = probe.getConfiguration();
        String status = SyncConstants.DS_SUCCESS;
        String remark = null;
        try {
            provider.checkStatus(probe);
        } catch (Exception e) {
            status = SyncConstants.DS_ERROR;
            remark = StringUtils.left(SyncJdbcSupport.rootMessage(e), 2000);
        }
        ds.setName(name);
        ds.setDescription(StringUtils.left(request.getDesc(), 512));
        ds.setType(type);
        ds.setDatasourceRole(role);
        ds.setConfiguration(configuration);
        ds.setStatus(status);
        ds.setStatusRemark(remark == null ? "" : remark);
    }

    /**
     * 前端传入 Base64(JSON)；兼容直接传 JSON
     */
    public static String decodeConfiguration(String configuration) {
        String value = StringUtils.trimToEmpty(configuration);
        if (value.isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        if (value.startsWith("{")) {
            return value;
        }
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            if (!json.trim().startsWith("{")) {
                DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
            }
            return json;
        } catch (IllegalArgumentException e) {
            DEException.throwException(Translator.get("ee_sync_ds_config_invalid"));
        }
        return null;
    }

    public void delete(Long oid, String id) {
        PerSyncDatasource ds = require(oid, id);
        if (!tasksUsing(ds.getId()).isEmpty()) {
            DEException.throwException(Translator.get("ee_sync_ds_in_use", ds.getName()));
        }
        datasourceRepository.deleteById(ds.getId());
    }

    public void batchDelete(Long oid, List<String> ids) {
        if (ids == null) {
            return;
        }
        List<PerSyncDatasource> list = ids.stream().map(id -> require(oid, id)).toList();
        for (PerSyncDatasource ds : list) {
            if (!tasksUsing(ds.getId()).isEmpty()) {
                DEException.throwException(Translator.get("ee_sync_ds_in_use", ds.getName()));
            }
        }
        datasourceRepository.deleteAllById(list.stream().map(PerSyncDatasource::getId).toList());
    }

    public List<PerSyncTask> tasksUsing(Long dsId) {
        return taskRepository.findAll((root, query, cb) -> cb.or(cb.equal(root.get("sourceDsId"), dsId), cb.equal(root.get("targetDsId"), dsId)));
    }

    // ------------------------------------------------------------------ 连接相关

    public DatasourceRequest internalRequest(PerSyncDatasource ds) {
        DatasourceRequest request = new DatasourceRequest();
        request.setId(String.valueOf(ds.getId()));
        request.setName(ds.getName());
        request.setType(ds.getType());
        request.setDatasourceRole(ds.getDatasourceRole());
        request.setConfiguration(ds.getConfiguration());
        return request;
    }

    private DatasourceRequest decodedRequest(DatasourceRequest request) {
        DatasourceRequest copy = new DatasourceRequest();
        org.springframework.beans.BeanUtils.copyProperties(request, copy);
        copy.setType(StringUtils.trimToEmpty(request.getType()));
        copy.setConfiguration(decodeConfiguration(request.getConfiguration()));
        return copy;
    }

    public String validate(DatasourceRequest request) {
        DatasourceRequest req = decodedRequest(request);
        SyncProvider provider = resolver.provider(req.getType(), req.getDatasourceRole());
        provider.validate(req);
        return SyncConstants.DS_SUCCESS;
    }

    public List<String> getSchema(DatasourceRequest request) {
        DatasourceRequest req = decodedRequest(request);
        return resolver.provider(req.getType(), req.getDatasourceRole()).getSchema(req);
    }

    /**
     * 检查已保存数据源，更新状态；可用返回 DTO，不可用返回 null
     */
    public DatasourceDTO validate(Long oid, String id) {
        PerSyncDatasource ds = require(oid, id);
        boolean ok = checkAndUpdate(ds);
        if (!ok) {
            return null;
        }
        DatasourceDTO dto = new DatasourceDTO();
        fill(dto, datasourceRepository.findById(ds.getId()).orElse(ds), null);
        return dto;
    }

    public boolean checkAndUpdate(PerSyncDatasource ds) {
        String status = SyncConstants.DS_SUCCESS;
        String remark = "";
        try {
            resolver.provider(ds.getType(), ds.getDatasourceRole()).checkStatus(internalRequest(ds));
        } catch (Exception e) {
            status = SyncConstants.DS_ERROR;
            remark = StringUtils.left(SyncJdbcSupport.rootMessage(e), 2000);
        }
        if (!Objects.equals(status, ds.getStatus()) || !Objects.equals(remark, StringUtils.defaultString(ds.getStatusRemark()))) {
            PerSyncDatasource patch = new PerSyncDatasource();
            patch.setId(ds.getId());
            patch.setStatus(status);
            patch.setStatusRemark(remark);
            datasourceRepository.save(patch);
        }
        return SyncConstants.DS_SUCCESS.equals(status);
    }

    /**
     * 定时检查全部同步数据源状态（由核心数据源状态检查任务触发）
     */
    public void checkAllStatus() {
        for (PerSyncDatasource ds : datasourceRepository.findAll()) {
            if (!checking.add(ds.getId())) {
                continue;
            }
            statusExecutor.submit(() -> {
                try {
                    checkAndUpdate(ds);
                } catch (Exception e) {
                    LogUtil.warn("check sync datasource status failed: " + ds.getName() + ", " + e.getMessage());
                } finally {
                    checking.remove(ds.getId());
                }
            });
        }
    }

    public List<DBTableDTO> tables(Long oid, String dsId) {
        PerSyncDatasource ds = require(oid, dsId);
        SourceProvider source = resolver.source(resolver.provider(ds.getType(), ds.getDatasourceRole()));
        List<TableDTO> tables = source.getTables(internalRequest(ds));
        return tables.stream().map(t -> {
            DBTableDTO dto = new DBTableDTO();
            dto.setDatasourceId(String.valueOf(ds.getId()));
            dto.setName(t.getName());
            dto.setRemark(t.getRemark());
            dto.setEnableCheck(true);
            return dto;
        }).toList();
    }

    /**
     * 源字段（含映射后的目标类型）与目标库类型列表
     */
    public Map<String, Object> fields(Long oid, DatasourceRequest request) {
        PerSyncDatasource sourceDs = require(oid, request.getId());
        SourceProvider source = resolver.source(resolver.provider(sourceDs.getType(), sourceDs.getDatasourceRole()));
        DatasourceRequest sourceReq = internalRequest(sourceDs);
        sourceReq.setTable(request.getTable());
        sourceReq.setQuery(request.getQuery());
        sourceReq.setTableExtract(request.isTableExtract());
        List<TableFieldDTO> fields;
        if (request.isTableExtract()) {
            if (StringUtils.isBlank(request.getTable())) {
                DEException.throwException(Translator.get("ee_sync_task_source_table_empty"));
            }
            fields = source.fetchTableField(sourceReq);
        } else {
            fields = source.getFieldsBySql(sourceReq);
        }
        List<String> targetTypes = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getTargetDbId())) {
            PerSyncDatasource targetDs = require(oid, request.getTargetDbId());
            SinkProvider sink = resolver.sink(resolver.provider(targetDs.getType(), targetDs.getDatasourceRole()));
            DatasourceRequest targetReq = internalRequest(targetDs);
            sink.dataTypeMapping(fields, sourceReq, targetReq);
            targetTypes = sink.dataTypes(targetReq);
            for (TableFieldDTO field : fields) {
                if (source.fieldMappingPolicy(field) == SourceFieldMappingPolicy.REQUIRE_MANUAL_MAPPING) {
                    field.setFieldType("UNKNOWN");
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("sourceFieldList", fields);
        result.put("targetDbDataTypeList", targetTypes);
        return result;
    }

    public List<Map<String, Object>> types() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (io.dataease.extensions.sync.vo.DatasourceConfiguration.DatasourceType t
                : io.dataease.extensions.sync.vo.DatasourceConfiguration.DatasourceType.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", t.getType());
            item.put("name", t.getName());
            item.put("catalog", t.getCatalog());
            item.put("datasourceRole", t.getDatasourceRole());
            result.add(item);
        }
        return result;
    }
}
