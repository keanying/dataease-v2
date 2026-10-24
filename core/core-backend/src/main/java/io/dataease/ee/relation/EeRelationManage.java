package io.dataease.ee.relation;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.relation.dto.RelationDTO;
import io.dataease.dao.auto.entity.*;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 血缘关系：数据源 → 数据集 → 仪表板/数据大屏。
 *
 * <ul>
 *     <li>数据集与数据源：core_dataset_table.datasource_id；</li>
 *     <li>仪表板/大屏与数据集：core_chart_view.table_id（数据集ID）与 scene_id（可视化资源ID），排除已删除到回收站的资源；</li>
 *     <li>只返回当前组织内、当前用户有查看（数据源为使用）权限的资源。</li>
 * </ul>
 */
@Component
public class EeRelationManage {

    public static final String TYPE_DATASOURCE = "datasource";

    public static final String TYPE_DATASET = "dataset";

    private final JPAQueryFactory queryFactory;

    private final EePermissionManage permissionManage;

    public EeRelationManage(JPAQueryFactory queryFactory, EePermissionManage permissionManage) {
        this.queryFactory = queryFactory;
        this.permissionManage = permissionManage;
    }

    /* ------------------------------------------------------------------ 接口 */

    public Map<String, Object> datasource(Long id) {
        Viewer viewer = new Viewer();
        CoreDatasource ds = datasourceEntity(id);
        viewer.require(EeConstants.RT_DATASOURCE, id, ds != null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", datasourceDto(ds, viewer));
        List<RelationDTO> list = new ArrayList<>();
        List<CoreDatasetGroup> datasets = datasets(datasetIdsByDatasource(List.of(id)), viewer);
        Map<Long, List<DataVisualizationInfo>> dvMap = visualizationsByDataset(ids(datasets, CoreDatasetGroup::getId), viewer);
        for (CoreDatasetGroup dataset : datasets) {
            RelationDTO dto = datasetDto(dataset, viewer);
            dto.setSubRelation(dvMap.getOrDefault(dataset.getId(), List.of()).stream().map(dv -> dvDto(dv, viewer)).toList());
            list.add(dto);
        }
        result.put("list", list);
        return result;
    }

    public Map<String, Object> dataset(Long id) {
        Viewer viewer = new Viewer();
        CoreDatasetGroup dataset = datasetEntity(id);
        viewer.require(EeConstants.RT_DATASET, id, dataset != null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", datasetDto(dataset, viewer));
        QCoreDatasetTable t = QCoreDatasetTable.coreDatasetTable;
        List<Long> dsIds = queryFactory.select(t.datasourceId).distinct().from(t)
                .where(t.datasetGroupId.eq(id), t.datasourceId.isNotNull()).fetch();
        result.put("dsList", datasources(dsIds, viewer).stream().map(ds -> datasourceDto(ds, viewer)).toList());
        result.put("dvList", visualizationsByDataset(List.of(id), viewer).getOrDefault(id, List.of()).stream()
                .map(dv -> dvDto(dv, viewer)).toList());
        return result;
    }

    public Map<String, Object> dv(Long id) {
        Viewer viewer = new Viewer();
        DataVisualizationInfo info = visualizationEntity(id);
        viewer.require(info == null ? EeConstants.RT_PANEL : rtOf(info), id, info != null && !Boolean.TRUE.equals(info.getDeleteFlag()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", dvDto(info, viewer));
        QCoreChartView c = QCoreChartView.coreChartView;
        List<Long> datasetIds = queryFactory.select(c.tableId).distinct().from(c)
                .where(c.sceneId.eq(id), c.tableId.isNotNull()).fetch();
        List<CoreDatasetGroup> datasets = datasets(datasetIds, viewer);
        Map<Long, CoreDatasetGroup> datasetById = new HashMap<>();
        datasets.forEach(item -> datasetById.put(item.getId(), item));
        // 数据源 → 该可视化资源中基于它的数据集
        Map<Long, LinkedHashSet<Long>> dsToDatasets = new LinkedHashMap<>();
        if (!datasetById.isEmpty()) {
            QCoreDatasetTable t = QCoreDatasetTable.coreDatasetTable;
            List<Tuple> rows = queryFactory.select(t.datasourceId, t.datasetGroupId).distinct().from(t)
                    .where(t.datasetGroupId.in(datasetById.keySet()), t.datasourceId.isNotNull()).fetch();
            for (Tuple row : rows) {
                dsToDatasets.computeIfAbsent(row.get(t.datasourceId), k -> new LinkedHashSet<>()).add(row.get(t.datasetGroupId));
            }
        }
        List<RelationDTO> list = new ArrayList<>();
        for (CoreDatasource ds : datasources(new ArrayList<>(dsToDatasets.keySet()), viewer)) {
            RelationDTO dto = datasourceDto(ds, viewer);
            dto.setSubRelation(dsToDatasets.get(ds.getId()).stream().map(datasetById::get).filter(Objects::nonNull)
                    .map(item -> datasetDto(item, viewer)).toList());
            list.add(dto);
        }
        result.put("list", list);
        return result;
    }

    /**
     * 使用该数据源（目录时包含其下全部数据源）的数据集数量（不区分权限，用于删除前提示）
     */
    public Long datasourceUsage(Long id) {
        if (id == null) {
            return 0L;
        }
        QCoreDatasource d = QCoreDatasource.coreDatasource;
        List<Long> ids = descendants(id, pid -> queryFactory.select(d.id).from(d).where(d.pid.in(pid)).fetch());
        QCoreDatasetTable t = QCoreDatasetTable.coreDatasetTable;
        Long count = queryFactory.select(t.datasetGroupId.countDistinct()).from(t).where(t.datasourceId.in(ids)).fetchOne();
        return count == null ? 0L : count;
    }

    /**
     * 使用该数据集（目录时包含其下全部数据集）的仪表板/大屏数量（不区分权限，用于删除前提示）
     */
    public Long datasetUsage(Long id) {
        if (id == null) {
            return 0L;
        }
        QCoreDatasetGroup g = QCoreDatasetGroup.coreDatasetGroup;
        List<Long> ids = descendants(id, pid -> queryFactory.select(g.id).from(g).where(g.pid.in(pid)).fetch());
        QCoreChartView c = QCoreChartView.coreChartView;
        QDataVisualizationInfo v = QDataVisualizationInfo.dataVisualizationInfo;
        Long count = queryFactory.select(v.id.countDistinct()).from(c).innerJoin(v).on(v.id.eq(c.sceneId))
                .where(c.tableId.in(ids), v.deleteFlag.isNull().or(v.deleteFlag.isFalse())).fetchOne();
        return count == null ? 0L : count;
    }

    /* ------------------------------------------------------------------ 查询 */

    private List<Long> descendants(Long id, java.util.function.Function<List<Long>, List<Long>> children) {
        List<Long> all = new ArrayList<>();
        all.add(id);
        List<Long> level = List.of(id);
        Set<Long> visited = new HashSet<>(all);
        for (int depth = 0; depth < 32 && !level.isEmpty(); depth++) {
            List<Long> next = children.apply(level).stream().filter(visited::add).toList();
            all.addAll(next);
            level = next;
        }
        return all;
    }

    private List<Long> datasetIdsByDatasource(List<Long> dsIds) {
        QCoreDatasetTable t = QCoreDatasetTable.coreDatasetTable;
        return queryFactory.select(t.datasetGroupId).distinct().from(t)
                .where(t.datasourceId.in(dsIds), t.datasetGroupId.isNotNull()).fetch();
    }

    private List<CoreDatasetGroup> datasets(Collection<Long> ids, Viewer viewer) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        QCoreDatasetGroup g = QCoreDatasetGroup.coreDatasetGroup;
        return queryFactory.selectFrom(g).where(g.id.in(ids)).orderBy(g.name.asc()).fetch().stream()
                .filter(item -> viewer.readable(EeConstants.RT_DATASET, item.getId())).toList();
    }

    private List<CoreDatasource> datasources(Collection<Long> ids, Viewer viewer) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        QCoreDatasource d = QCoreDatasource.coreDatasource;
        return queryFactory.selectFrom(d).where(d.id.in(ids)).orderBy(d.name.asc()).fetch().stream()
                .filter(item -> viewer.readable(EeConstants.RT_DATASOURCE, item.getId())).toList();
    }

    private Map<Long, List<DataVisualizationInfo>> visualizationsByDataset(Collection<Long> datasetIds, Viewer viewer) {
        Map<Long, List<DataVisualizationInfo>> result = new HashMap<>();
        if (CollectionUtils.isEmpty(datasetIds)) {
            return result;
        }
        QCoreChartView c = QCoreChartView.coreChartView;
        List<Tuple> rows = queryFactory.select(c.tableId, c.sceneId).distinct().from(c)
                .where(c.tableId.in(datasetIds), c.sceneId.isNotNull()).fetch();
        if (rows.isEmpty()) {
            return result;
        }
        Set<Long> sceneIds = new HashSet<>();
        rows.forEach(row -> sceneIds.add(row.get(c.sceneId)));
        QDataVisualizationInfo v = QDataVisualizationInfo.dataVisualizationInfo;
        Map<Long, DataVisualizationInfo> dvById = new LinkedHashMap<>();
        queryFactory.selectFrom(v).where(v.id.in(sceneIds), v.deleteFlag.isNull().or(v.deleteFlag.isFalse()))
                .orderBy(v.name.asc()).fetch().stream()
                .filter(item -> viewer.readable(rtOf(item), item.getId()))
                .forEach(item -> dvById.put(item.getId(), item));
        for (Tuple row : rows) {
            DataVisualizationInfo dv = dvById.get(row.get(c.sceneId));
            if (dv != null) {
                result.computeIfAbsent(row.get(c.tableId), k -> new ArrayList<>()).add(dv);
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(item -> StringUtils.defaultString(item.getName()))));
        return result;
    }

    private CoreDatasource datasourceEntity(Long id) {
        QCoreDatasource d = QCoreDatasource.coreDatasource;
        return queryFactory.selectFrom(d).where(d.id.eq(id)).fetchFirst();
    }

    private CoreDatasetGroup datasetEntity(Long id) {
        QCoreDatasetGroup g = QCoreDatasetGroup.coreDatasetGroup;
        return queryFactory.selectFrom(g).where(g.id.eq(id)).fetchFirst();
    }

    private DataVisualizationInfo visualizationEntity(Long id) {
        QDataVisualizationInfo v = QDataVisualizationInfo.dataVisualizationInfo;
        return queryFactory.selectFrom(v).where(v.id.eq(id)).fetchFirst();
    }

    private static int rtOf(DataVisualizationInfo info) {
        return StringUtils.equalsIgnoreCase(info.getType(), "dataV") ? EeConstants.RT_SCREEN : EeConstants.RT_PANEL;
    }

    private static <T> List<Long> ids(List<T> list, java.util.function.Function<T, Long> getter) {
        return list.stream().map(getter).filter(Objects::nonNull).toList();
    }

    /* ------------------------------------------------------------------ DTO */

    private RelationDTO datasourceDto(CoreDatasource ds, Viewer viewer) {
        RelationDTO dto = new RelationDTO();
        dto.setId(ds.getId());
        dto.setName(ds.getName());
        dto.setType(TYPE_DATASOURCE);
        dto.setCreator(viewer.userName(ds.getCreateBy()));
        dto.setUpdateTime(ds.getUpdateTime() != null ? ds.getUpdateTime() : ds.getCreateTime());
        dto.setAuths(viewer.auths(EeConstants.RT_DATASOURCE, ds.getId()));
        return dto;
    }

    private RelationDTO datasetDto(CoreDatasetGroup dataset, Viewer viewer) {
        RelationDTO dto = new RelationDTO();
        dto.setId(dataset.getId());
        dto.setName(dataset.getName());
        dto.setType(TYPE_DATASET);
        dto.setCreator(viewer.userName(dataset.getCreateBy()));
        dto.setUpdateTime(dataset.getLastUpdateTime() != null ? dataset.getLastUpdateTime() : dataset.getCreateTime());
        dto.setAuths(viewer.auths(EeConstants.RT_DATASET, dataset.getId()));
        return dto;
    }

    private RelationDTO dvDto(DataVisualizationInfo info, Viewer viewer) {
        RelationDTO dto = new RelationDTO();
        dto.setId(info.getId());
        dto.setName(info.getName());
        dto.setType(rtOf(info) == EeConstants.RT_SCREEN ? "dataV" : "dashboard");
        dto.setCreator(viewer.userName(info.getCreateBy()));
        dto.setUpdateTime(info.getUpdateTime() != null ? info.getUpdateTime() : info.getCreateTime());
        dto.setAuths(viewer.auths(rtOf(info), info.getId()));
        return dto;
    }

    /**
     * 当前查看者：缓存各类资源的有效权限与用户名
     */
    private class Viewer {

        private final Long uid = V3UserUtil.getUid();

        private final Long oid = V3UserUtil.getOid();

        private final Map<Integer, Map<Long, Perm>> perms = new HashMap<>();

        private final Map<String, String> names = new HashMap<>();

        Viewer() {
            if (uid == null || oid == null) {
                DEException.throwException(Translator.get("ee_no_permission"));
            }
        }

        Perm perm(int rtId, Long id) {
            return perms.computeIfAbsent(rtId, rt -> permissionManage.effective(uid, oid, rt)).getOrDefault(id, Perm.NONE);
        }

        boolean readable(int rtId, Long id) {
            return perm(rtId, id).satisfies(EeConstants.WEIGHT_READ);
        }

        void require(int rtId, Long id, boolean exists) {
            if (!exists) {
                DEException.throwException(Translator.get("ee_relation_resource_not_exist"));
            }
            if (!readable(rtId, id)) {
                DEException.throwException(Translator.get("ee_no_permission"));
            }
        }

        String auths(int rtId, Long id) {
            return String.valueOf(perm(rtId, id).weight());
        }

        String userName(String createBy) {
            if (StringUtils.isBlank(createBy)) {
                return null;
            }
            return names.computeIfAbsent(createBy, key -> {
                if (!StringUtils.isNumeric(key) || key.length() > 19) {
                    return key;
                }
                V3BaseUser user = V3UserUtil.getUser(Long.parseLong(key));
                return user == null ? key : user.getName();
            });
        }
    }
}
