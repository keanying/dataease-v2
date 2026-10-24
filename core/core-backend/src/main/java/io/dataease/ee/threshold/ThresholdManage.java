package io.dataease.ee.threshold;

import io.dataease.api.chart.request.ThresholdCheckRequest;
import io.dataease.api.chart.vo.ThresholdCheckVO;
import io.dataease.api.threshold.dto.*;
import io.dataease.api.threshold.vo.ThresholdGridVO;
import io.dataease.api.threshold.vo.ThresholdInstanceVO;
import io.dataease.chart.dao.auto.mapper.CoreChartViewRepository;
import io.dataease.chart.manage.ChartViewThresholdManage;
import io.dataease.constant.CommonConstants;
import io.dataease.dao.auto.entity.CoreChartView;
import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.report.EeRateUtils;
import io.dataease.ee.report.ReportConstants;
import io.dataease.ee.report.ReportManage;
import io.dataease.ee.setting.EeSettingManage;
import io.dataease.ee.threshold.dao.*;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.job.schedule.DeTaskExecutor;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.BeanUtils;
import io.dataease.utils.IDUtils;
import io.dataease.utils.LogUtil;
import io.dataease.visualization.dao.auto.entity.SnapshotCoreChartView;
import io.dataease.visualization.dao.auto.entity.SnapshotDataVisualizationInfo;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoRepository;
import io.dataease.visualization.dao.auto.mapper.SnapshotCoreChartViewRepository;
import io.dataease.visualization.dao.auto.mapper.SnapshotDataVisualizationInfoRepository;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 阈值告警管理。
 *
 * <p>编辑器中的告警写入镜像表（snapshot），发布仪表板时由核心钩子复制到正式表（core），
 * 只有正式表中启用的告警会注册检测任务（Quartz 组 THRESHOLD_TASK）。两张表中同一告警 ID 相同。</p>
 */
@Component
public class ThresholdManage {

    public static final String CORE = CommonConstants.RESOURCE_TABLE.CORE;

    public static final String SNAPSHOT = CommonConstants.RESOURCE_TABLE.SNAPSHOT;

    private final XpackThresholdInfoRepository coreRepository;

    private final XpackThresholdInfoSnapshotRepository snapshotRepository;

    private final XpackThresholdInstanceRepository instanceRepository;

    private final EePermissionManage permissionManage;

    private final ReportManage reportManage;

    private final EeSettingManage settingManage;

    private final DataVisualizationInfoRepository dvRepository;

    private final SnapshotDataVisualizationInfoRepository snapshotDvRepository;

    private final CoreChartViewRepository chartRepository;

    private final SnapshotCoreChartViewRepository snapshotChartRepository;

    private final ChartViewThresholdManage chartViewThresholdManage;

    private final DeTaskExecutor deTaskExecutor;

    private final TransactionTemplate transactionTemplate;

    public ThresholdManage(XpackThresholdInfoRepository coreRepository, XpackThresholdInfoSnapshotRepository snapshotRepository,
                           XpackThresholdInstanceRepository instanceRepository, EePermissionManage permissionManage,
                           ReportManage reportManage, EeSettingManage settingManage, DataVisualizationInfoRepository dvRepository,
                           SnapshotDataVisualizationInfoRepository snapshotDvRepository, CoreChartViewRepository chartRepository,
                           SnapshotCoreChartViewRepository snapshotChartRepository,
                           @Lazy ChartViewThresholdManage chartViewThresholdManage, @Lazy DeTaskExecutor deTaskExecutor,
                           TransactionTemplate transactionTemplate) {
        this.coreRepository = coreRepository;
        this.snapshotRepository = snapshotRepository;
        this.instanceRepository = instanceRepository;
        this.permissionManage = permissionManage;
        this.reportManage = reportManage;
        this.settingManage = settingManage;
        this.dvRepository = dvRepository;
        this.snapshotDvRepository = snapshotDvRepository;
        this.chartRepository = chartRepository;
        this.snapshotChartRepository = snapshotChartRepository;
        this.chartViewThresholdManage = chartViewThresholdManage;
        this.deTaskExecutor = deTaskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------ 存储抽象

    private record Store<T extends ThresholdInfoBase>(JpaRepository<T, Long> repo, JpaSpecificationExecutor<T> spec,
                                                     Supplier<T> factory, boolean core) {
    }

    private Store<? extends ThresholdInfoBase> store(String table) {
        if (StringUtils.equalsIgnoreCase(SNAPSHOT, table)) {
            return new Store<>(snapshotRepository, snapshotRepository, XpackThresholdInfoSnapshot::new, false);
        }
        return new Store<>(coreRepository, coreRepository, XpackThresholdInfo::new, true);
    }

    private static String table(String table) {
        return StringUtils.equalsIgnoreCase(SNAPSHOT, table) ? SNAPSHOT : CORE;
    }

    @SuppressWarnings("unchecked")
    private <T extends ThresholdInfoBase> void save(Store<T> store, ThresholdInfoBase entity) {
        store.repo().save((T) entity);
    }

    // ------------------------------------------------------------------ 权限

    private boolean orgAdmin() {
        return permissionManage.isOrgAdmin(V3UserUtil.getUid(), V3UserUtil.getOid());
    }

    public static int resourceRt(String resourceType) {
        return StringUtils.equalsIgnoreCase("dataV", resourceType) ? EeConstants.RT_SCREEN : EeConstants.RT_PANEL;
    }

    private boolean canManageResource(String resourceType, Long resourceId) {
        return resourceId != null && permissionManage.current(resourceRt(resourceType), resourceId).satisfies(EeConstants.WEIGHT_MANAGE);
    }

    /**
     * 可管理：同组织，且为创建人、组织管理员或对所属仪表板有管理权限
     */
    private void checkManage(ThresholdInfoBase info) {
        if (!Objects.equals(info.getOid(), V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        if (Objects.equals(info.getCreator(), V3UserUtil.getUid()) || orgAdmin()
                || canManageResource(info.getResourceType(), info.getResourceId())) {
            return;
        }
        DEException.throwException(Translator.get("ee_no_permission"));
    }

    private ThresholdInfoBase require(Long id, String table) {
        ThresholdInfoBase info = id == null ? null : store(table).repo().findById(id).orElse(null);
        if (info == null) {
            DEException.throwException(Translator.get("ee_threshold_not_exist"));
        }
        return info;
    }

    // ------------------------------------------------------------------ 列表

    public PageResult<ThresholdGridVO> pager(int goPage, int pageSize, ThresholdGridRequest request) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        ThresholdGridRequest req = request == null ? new ThresholdGridRequest() : request;
        String table = table(req.getResourceTable());
        Long oid = V3UserUtil.getOid();
        Long uid = V3UserUtil.getUid();
        boolean admin = orgAdmin();
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(oid == null ? cb.disjunction() : cb.equal(root.get("oid"), oid));
            if (req.getChartId() != null) {
                predicates.add(cb.equal(root.get("chartId"), req.getChartId()));
            } else if (!admin) {
                predicates.add(cb.equal(root.get("creator"), uid));
            }
            if (StringUtils.isNotBlank(req.getKeyword())) {
                predicates.add(cb.like(root.get("name"), "%" + req.getKeyword().trim() + "%"));
            }
            if (CollectionUtils.isNotEmpty(req.getResourceTypeList())) {
                predicates.add(root.get("resourceType").in(req.getResourceTypeList()));
            }
            if (CollectionUtils.isNotEmpty(req.getEnableList())) {
                List<Boolean> values = req.getEnableList().stream().filter(Objects::nonNull).map(v -> v == 1).distinct().toList();
                if (values.size() == 1) {
                    predicates.add(values.getFirst() ? cb.isTrue(root.get("enable")) : cb.or(cb.isFalse(root.get("enable")), cb.isNull(root.get("enable"))));
                }
            }
            if (CollectionUtils.isNotEmpty(req.getStatusList())) {
                List<Boolean> values = req.getStatusList().stream().filter(Objects::nonNull).map(v -> v == 1).distinct().toList();
                if (values.size() == 1) {
                    predicates.add(values.getFirst() ? cb.isTrue(root.get("status")) : cb.or(cb.isFalse(root.get("status")), cb.isNull(root.get("status"))));
                }
            }
            List<Long> timeList = req.getTimeList();
            if (CollectionUtils.isNotEmpty(timeList) && timeList.size() >= 2 && timeList.get(0) != null && timeList.get(1) != null) {
                predicates.add(cb.between(root.get("createTime"), timeList.get(0), timeList.get(1)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(Sort.Direction.DESC, "createTime");
        PageRequest pageable = PageRequest.of(page - 1, size, sort);
        Page<? extends ThresholdInfoBase> result = findPage(store(table), spec, pageable);
        List<? extends ThresholdInfoBase> list = result.getContent();
        Map<Long, String> names = reportManage.userNames(list.stream().map(ThresholdInfoBase::getCreator).collect(Collectors.toSet()));
        List<ThresholdGridVO> records = new ArrayList<>();
        for (ThresholdInfoBase info : list) {
            ThresholdGridVO vo = new ThresholdGridVO();
            vo.setId(info.getId());
            vo.setName(info.getName());
            vo.setResourceId(info.getResourceId());
            vo.setResourceType(info.getResourceType());
            vo.setResourceName(resourceName(info.getResourceId(), table));
            vo.setChartId(info.getChartId());
            vo.setChartType(info.getChartType());
            vo.setChartName(chartName(info.getChartId(), table));
            vo.setStatus(Boolean.TRUE.equals(info.getStatus()));
            vo.setEnable(Boolean.TRUE.equals(info.getEnable()));
            vo.setCreator(info.getCreator());
            vo.setCreateName(names.get(info.getCreator()));
            vo.setCreateTime(info.getCreateTime());
            records.add(vo);
        }
        return new PageResult<>(records, result.getTotalElements(), pageable);
    }

    @SuppressWarnings("unchecked")
    private <T extends ThresholdInfoBase> Page<T> findPage(Store<T> store, Specification<ThresholdInfoBase> spec, PageRequest pageable) {
        return store.spec().findAll((Specification<T>) (Specification<?>) spec, pageable);
    }

    @SuppressWarnings("unchecked")
    private <T extends ThresholdInfoBase> List<T> findAll(Store<T> store, Specification<ThresholdInfoBase> spec) {
        return store.spec().findAll((Specification<T>) (Specification<?>) spec);
    }

    private String resourceName(Long resourceId, String table) {
        if (resourceId == null) {
            return null;
        }
        if (SNAPSHOT.equals(table)) {
            SnapshotDataVisualizationInfo info = snapshotDvRepository.findById(resourceId).orElse(null);
            if (info != null) {
                return info.getName();
            }
        }
        DataVisualizationInfo info = dvRepository.findById(resourceId).orElse(null);
        return info == null ? null : info.getName();
    }

    private String chartName(Long chartId, String table) {
        if (chartId == null) {
            return null;
        }
        if (SNAPSHOT.equals(table)) {
            SnapshotCoreChartView view = snapshotChartRepository.findById(chartId).orElse(null);
            if (view != null) {
                return view.getTitle();
            }
        }
        CoreChartView view = chartRepository.findById(chartId).orElse(null);
        return view == null ? null : view.getTitle();
    }

    // ------------------------------------------------------------------ 增删改

    public void save(ThresholdCreator creator) {
        validate(creator);
        if (!canManageResource(creator.getResourceType(), creator.getResourceId()) && !orgAdmin()) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        String table = table(creator.getResourceTable());
        Store<? extends ThresholdInfoBase> store = store(table);
        ThresholdInfoBase info = store.factory().get();
        fill(info, creator);
        long now = System.currentTimeMillis();
        info.setId(IDUtils.snowID());
        info.setStatus(false);
        info.setOid(V3UserUtil.getOid());
        info.setCreator(V3UserUtil.getUid());
        info.setCreateTime(now);
        info.setUpdater(V3UserUtil.getUid());
        info.setUpdateTime(now);
        save(store, info);
        if (store.core()) {
            schedule(info);
        }
    }

    public void edit(ThresholdCreator creator) {
        String table = table(creator.getResourceTable());
        ThresholdInfoBase info = require(creator.getId(), table);
        checkManage(info);
        validate(creator);
        fill(info, creator);
        info.setUpdater(V3UserUtil.getUid());
        info.setUpdateTime(System.currentTimeMillis());
        save(store(table), info);
        if (CORE.equals(table)) {
            mirrorToSnapshot(info);
            schedule(info);
        }
    }

    /**
     * 系统菜单直接修改正式表时同步镜像表中的同一告警，避免下次发布被旧镜像覆盖
     */
    private void mirrorToSnapshot(ThresholdInfoBase info) {
        snapshotRepository.findById(info.getId()).ifPresent(snapshot -> {
            BeanUtils.copyBean(snapshot, info);
            snapshotRepository.save(snapshot);
        });
    }

    private void validate(ThresholdCreator creator) {
        if (StringUtils.isBlank(creator.getName()) || creator.getName().trim().length() > 100) {
            DEException.throwException(Translator.get("ee_threshold_name_invalid"));
        }
        if (creator.getChartId() == null || creator.getResourceId() == null) {
            DEException.throwException(Translator.get("ee_threshold_chart_required"));
        }
        if (StringUtils.isBlank(creator.getThresholdRules())) {
            DEException.throwException(Translator.get("ee_threshold_rules_required"));
        }
        EeRateUtils.thresholdCron(creator.getRateType(), creator.getRateValue());
        validateReci(creator);
    }

    private void validateReci(BaseReciDTO reci) {
        boolean users = CollectionUtils.isNotEmpty(reci.getUidList()) || CollectionUtils.isNotEmpty(reci.getRidList());
        boolean any = users || CollectionUtils.isNotEmpty(reci.getEmailList()) || CollectionUtils.isNotEmpty(reci.getLarkGroupList())
                || CollectionUtils.isNotEmpty(reci.getLarksuiteGroupList()) || CollectionUtils.isNotEmpty(reci.getWebhookList());
        if (!any) {
            DEException.throwException(Translator.get("ee_report_recipient_required"));
        }
        if (users && CollectionUtils.isEmpty(reci.getReciFlagList())) {
            DEException.throwException(Translator.get("ee_report_channel_required"));
        }
    }

    private void fill(ThresholdInfoBase info, ThresholdCreator creator) {
        info.setName(creator.getName().trim());
        info.setEnable(!Boolean.FALSE.equals(creator.getEnable()));
        info.setRateType(ObjectUtils.defaultIfNull(creator.getRateType(), 1));
        info.setRateValue(creator.getRateValue());
        info.setResourceId(creator.getResourceId());
        info.setResourceType(StringUtils.defaultIfBlank(creator.getResourceType(), "dashboard"));
        info.setChartId(creator.getChartId());
        info.setChartType(creator.getChartType());
        info.setThresholdRules(creator.getThresholdRules());
        info.setMsgType(ObjectUtils.defaultIfNull(creator.getMsgType(), 0));
        info.setMsgTitle(creator.getMsgTitle());
        info.setMsgContent(creator.getMsgContent());
        info.setRepeatSend(!Boolean.FALSE.equals(creator.getRepeatSend()));
        info.setShowFieldValue(Boolean.TRUE.equals(creator.getShowFieldValue()));
        fillReci(info, creator);
    }

    private void fillReci(ThresholdInfoBase info, BaseReciDTO reci) {
        info.setReciFlags(ReportConstants.toJson(reci.getReciFlagList()));
        info.setUids(ReportConstants.toJson(ReportConstants.parseIds(reci.getUidList())));
        info.setRids(ReportConstants.toJson(ReportConstants.parseIds(reci.getRidList())));
        info.setEmails(ReportConstants.toJson(reci.getEmailList() == null ? null
                : reci.getEmailList().stream().filter(StringUtils::isNotBlank).map(String::trim).distinct().toList()));
        info.setLarkGroups(ReportConstants.toJson(reci.getLarkGroupList()));
        info.setLarksuiteGroups(ReportConstants.toJson(reci.getLarksuiteGroupList()));
        info.setWebhooks(ReportConstants.toJson(reci.getWebhookList()));
    }

    public ThresholdCreator formInfo(Long id, String resourceTable) {
        String table = table(resourceTable);
        ThresholdInfoBase info = require(id, table);
        if (!Objects.equals(info.getOid(), V3UserUtil.getOid())) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        ThresholdCreator vo = new ThresholdCreator();
        vo.setId(info.getId());
        vo.setName(info.getName());
        vo.setEnable(info.getEnable());
        vo.setRateType(info.getRateType());
        vo.setRateValue(info.getRateValue());
        vo.setResourceId(info.getResourceId());
        vo.setResourceType(info.getResourceType());
        vo.setChartId(info.getChartId());
        vo.setChartType(info.getChartType());
        vo.setThresholdRules(info.getThresholdRules());
        vo.setMsgType(info.getMsgType());
        vo.setMsgTitle(info.getMsgTitle());
        vo.setMsgContent(info.getMsgContent());
        vo.setRepeatSend(info.getRepeatSend());
        vo.setShowFieldValue(info.getShowFieldValue());
        vo.setResourceTable(table);
        vo.setReciFlagList(ReportConstants.ints(info.getReciFlags()));
        vo.setUidList(ReportConstants.idStrings(info.getUids()));
        vo.setRidList(ReportConstants.idStrings(info.getRids()));
        vo.setEmailList(ReportConstants.strings(info.getEmails()));
        vo.setLarkGroupList(ReportConstants.strings(info.getLarkGroups()));
        vo.setLarksuiteGroupList(ReportConstants.strings(info.getLarksuiteGroups()));
        vo.setWebhookList(ReportConstants.strings(info.getWebhooks()));
        return vo;
    }

    public void switchEnable(ThresholdSwitchRequest request) {
        String table = table(request.getResourceTable());
        ThresholdInfoBase info = require(request.getId(), table);
        checkManage(info);
        info.setEnable(Boolean.TRUE.equals(request.getEnable()));
        info.setUpdater(V3UserUtil.getUid());
        info.setUpdateTime(System.currentTimeMillis());
        save(store(table), info);
        if (CORE.equals(table)) {
            mirrorToSnapshot(info);
            schedule(info);
        }
    }

    public void delete(List<Long> ids, String resourceTable) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        String table = table(resourceTable);
        for (Long id : new LinkedHashSet<>(ids)) {
            ThresholdInfoBase info = store(table).repo().findById(id).orElse(null);
            if (info == null) {
                continue;
            }
            checkManage(info);
            remove(info, table, CORE.equals(table));
        }
    }

    /**
     * 删除一条告警；正式表删除时取消检测任务，purge=true 同时删除检测记录与镜像
     */
    private void remove(ThresholdInfoBase info, String table, boolean purge) {
        store(table).repo().deleteById(info.getId());
        if (CORE.equals(table)) {
            deTaskExecutor.removeThresholdTask(info.getId());
            if (purge) {
                snapshotRepository.deleteById(info.getId());
                deleteInstances(List.of(info.getId()));
            }
        }
    }

    private void deleteInstances(List<Long> thresholdIds) {
        if (thresholdIds.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(s -> {
            Specification<XpackThresholdInstance> spec = (root, query, cb) -> root.get("thresholdId").in(thresholdIds);
            instanceRepository.deleteAll(instanceRepository.findAll(spec));
        });
    }

    public void batchReci(ThresholdBatchReciRequest request) {
        if (CollectionUtils.isEmpty(request.getIdList())) {
            return;
        }
        validateReci(request);
        for (Long id : new LinkedHashSet<>(request.getIdList())) {
            ThresholdInfoBase info = require(id, CORE);
            checkManage(info);
            fillReci(info, request);
            info.setUpdater(V3UserUtil.getUid());
            info.setUpdateTime(System.currentTimeMillis());
            coreRepository.save((XpackThresholdInfo) info);
            mirrorToSnapshot(info);
        }
    }

    public boolean anyThreshold(Long chartId, String resourceTable) {
        if (chartId == null) {
            return false;
        }
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("chartId"), chartId);
        return !findAll(store(table(resourceTable)), spec).isEmpty();
    }

    public void deleteWithChart(Long chartId, String resourceTable) {
        if (chartId == null) {
            return;
        }
        String table = table(resourceTable);
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("chartId"), chartId);
        for (ThresholdInfoBase info : findAll(store(table), spec)) {
            checkManage(info);
            remove(info, table, CORE.equals(table));
        }
    }

    /**
     * 编辑器通过 thresholdToken 打开时：把图表的正式告警复制到镜像表
     */
    public void snapshotRestore(Long chartId) {
        if (chartId == null) {
            return;
        }
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("chartId"), chartId);
        List<? extends ThresholdInfoBase> cores = findAll(store(CORE), spec);
        transactionTemplate.executeWithoutResult(s -> {
            findAll(store(SNAPSHOT), spec).forEach(item -> snapshotRepository.deleteById(item.getId()));
            snapshotRepository.flush();
            for (ThresholdInfoBase core : cores) {
                XpackThresholdInfoSnapshot snapshot = new XpackThresholdInfoSnapshot();
                BeanUtils.copyBean(snapshot, core);
                snapshotRepository.save(snapshot);
            }
        });
    }

    // ------------------------------------------------------------------ 预览与记录

    public String preview(ThresholdPreviewRequest request) {
        if (request.getChartId() == null || StringUtils.isBlank(request.getThresholdRules()) || StringUtils.isBlank(request.getMsgContent())) {
            return null;
        }
        try {
            ThresholdCheckRequest check = new ThresholdCheckRequest();
            check.setChartId(request.getChartId());
            check.setThresholdRules(request.getThresholdRules());
            check.setThresholdTemplate(request.getMsgContent());
            check.setResourceTable(table(request.getResourceTable()));
            check.setShowFieldValue(true);
            check.setThresholdLimit(thresholdLimit());
            ThresholdCheckVO vo = chartViewThresholdManage.checkThreshold(check);
            return vo == null ? null : vo.getContent();
        } catch (Exception e) {
            LogUtil.error("threshold preview failed: " + e.getMessage(), e);
            return null;
        }
    }

    public int thresholdLimit() {
        int limit = settingManage.getInt("basic.thresholdLimit", 10);
        return Math.max(1, Math.min(50, limit));
    }

    public PageResult<ThresholdInstanceVO> instancePager(int goPage, int pageSize, ThresholdInstanceRequest request) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        Long oid = V3UserUtil.getOid();
        Long uid = V3UserUtil.getUid();
        boolean admin = orgAdmin();
        // 可见的告警
        Specification<ThresholdInfoBase> infoSpec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(oid == null ? cb.disjunction() : cb.equal(root.get("oid"), oid));
            if (!admin) {
                predicates.add(cb.equal(root.get("creator"), uid));
            }
            if (request != null && request.getThresholdId() != null) {
                predicates.add(cb.equal(root.get("id"), request.getThresholdId()));
            } else if (request != null && StringUtils.isNotBlank(request.getKeyword())) {
                predicates.add(cb.like(root.get("name"), "%" + request.getKeyword().trim() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Map<Long, String> names = new HashMap<>();
        findAll(store(CORE), infoSpec).forEach(info -> names.put(info.getId(), info.getName()));
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "execTime"));
        if (names.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), 0L, pageable);
        }
        List<Long> ids = new ArrayList<>(names.keySet());
        Specification<XpackThresholdInstance> spec = (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>();
            for (int k = 0; k < ids.size(); k += 500) {
                ors.add(root.get("thresholdId").in(ids.subList(k, Math.min(ids.size(), k + 500))));
            }
            return cb.or(ors.toArray(new Predicate[0]));
        };
        Page<XpackThresholdInstance> result = instanceRepository.findAll(spec, pageable);
        List<ThresholdInstanceVO> records = result.getContent().stream().map(instance -> {
            ThresholdInstanceVO vo = new ThresholdInstanceVO();
            vo.setId(instance.getId());
            vo.setTaskId(instance.getThresholdId());
            vo.setName(names.get(instance.getThresholdId()));
            vo.setExecTime(instance.getExecTime());
            vo.setStatus(Boolean.TRUE.equals(instance.getStatus()));
            vo.setContent(instance.getContent());
            vo.setMsg(instance.getMsg());
            return vo;
        }).toList();
        return new PageResult<>(records, result.getTotalElements(), pageable);
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 正式表告警：启用则注册/更新检测任务，否则取消
     */
    public void schedule(ThresholdInfoBase info) {
        try {
            if (Boolean.TRUE.equals(info.getEnable())) {
                deTaskExecutor.addThresholdTask(info.getId(), EeRateUtils.thresholdCron(info.getRateType(), info.getRateValue()));
            } else {
                deTaskExecutor.removeThresholdTask(info.getId());
            }
        } catch (Exception e) {
            LogUtil.error("schedule threshold " + info.getId() + " failed: " + e.getMessage(), e);
        }
    }

    public void initSchedules() {
        List<XpackThresholdInfo> list = coreRepository.findAll();
        int count = 0;
        for (XpackThresholdInfo info : list) {
            if (Boolean.TRUE.equals(info.getEnable())) {
                schedule(info);
                count++;
            }
        }
        LogUtil.info("threshold tasks scheduled: " + count);
    }

    // ------------------------------------------------------------------ 核心钩子

    public void removeByResource(Long resourceId, String resourceTable) {
        if (resourceId == null) {
            return;
        }
        String table = table(resourceTable);
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("resourceId"), resourceId);
        for (ThresholdInfoBase info : findAll(store(table), spec)) {
            remove(info, table, false);
        }
    }

    /**
     * 恢复：core ← snapshot（发布），snapshot ← core（恢复编辑镜像）
     */
    public void restoreByResource(Long resourceId, String resourceTable) {
        if (resourceId == null) {
            return;
        }
        String target = table(resourceTable);
        String source = CORE.equals(target) ? SNAPSHOT : CORE;
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("resourceId"), resourceId);
        List<? extends ThresholdInfoBase> sources = findAll(store(source), spec);
        List<ThresholdInfoBase> saved = new ArrayList<>();
        transactionTemplate.executeWithoutResult(s -> {
            Store<? extends ThresholdInfoBase> targetStore = store(target);
            List<? extends ThresholdInfoBase> existing = findAll(targetStore, spec);
            existing.forEach(item -> targetStore.repo().deleteById(item.getId()));
            targetStore.repo().flush();
            for (ThresholdInfoBase item : sources) {
                ThresholdInfoBase copy = targetStore.factory().get();
                BeanUtils.copyBean(copy, item);
                save(targetStore, copy);
                saved.add(copy);
            }
        });
        if (CORE.equals(target)) {
            saved.forEach(this::schedule);
        }
    }

    /**
     * 发布：删除已不在仪表板中的图表的告警，并刷新检测任务
     */
    public void publish(Long resourceId, List<Long> chartIdList) {
        if (resourceId == null) {
            return;
        }
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> cb.equal(root.get("resourceId"), resourceId);
        if (chartIdList != null) {
            Set<Long> active = new HashSet<>(chartIdList);
            for (String table : List.of(SNAPSHOT, CORE)) {
                for (ThresholdInfoBase info : findAll(store(table), spec)) {
                    if (!active.contains(info.getChartId())) {
                        remove(info, table, CORE.equals(table));
                    }
                }
            }
        }
        findAll(store(CORE), spec).forEach(this::schedule);
    }

    public void disuse(List<Long> chartIdList) {
        if (CollectionUtils.isEmpty(chartIdList)) {
            return;
        }
        Specification<ThresholdInfoBase> spec = (root, query, cb) -> root.get("chartId").in(chartIdList);
        for (String table : List.of(SNAPSHOT, CORE)) {
            for (ThresholdInfoBase info : findAll(store(table), spec)) {
                remove(info, table, CORE.equals(table));
            }
        }
    }

    public XpackThresholdInfo findCore(Long id) {
        return id == null ? null : coreRepository.findById(id).orElse(null);
    }
}
