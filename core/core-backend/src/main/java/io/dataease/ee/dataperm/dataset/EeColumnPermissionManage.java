package io.dataease.ee.dataperm.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.dataset.dto.DataSetColumnPermissionsDTO;
import io.dataease.constant.ColumnPermissionConstants;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.dao.entity.DatasetColumnPermission;
import io.dataease.ee.dataperm.dao.entity.QDatasetColumnPermission;
import io.dataease.ee.dataperm.dao.repo.DatasetColumnPermissionRepository;
import io.dataease.exception.DEException;
import io.dataease.extensions.view.dto.ColumnPermissionItem;
import io.dataease.extensions.view.dto.ColumnPermissions;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 数据集列权限：禁用列（Prohibit）与脱敏（Desensitization）规则，授权对象为用户或角色。
 */
@Component
public class EeColumnPermissionManage {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JPAQueryFactory queryFactory;

    private final DatasetColumnPermissionRepository repository;

    private final EeDatapermSupport support;

    public EeColumnPermissionManage(JPAQueryFactory queryFactory, DatasetColumnPermissionRepository repository, EeDatapermSupport support) {
        this.queryFactory = queryFactory;
        this.repository = repository;
        this.support = support;
    }

    public PageResult<DataSetColumnPermissionsDTO> page(Long datasetId, int goPage, int pageSize) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        var query = queryFactory.selectFrom(q).where(q.datasetId.eq(datasetId));
        long total = query.clone().fetchCount();
        List<DatasetColumnPermission> rows = query.orderBy(q.updateTime.desc(), q.id.desc())
                .offset((long) (page - 1) * size).limit(size).fetch();
        return new PageResult<>(enrich(rows), total, PageRequest.of(page - 1, size));
    }

    public DatasetColumnPermission require(Long id) {
        DatasetColumnPermission row = id == null ? null : repository.findById(id).orElse(null);
        if (row == null) {
            DEException.throwException(Translator.get("ee_dataperm_rule_not_exist"));
        }
        return row;
    }

    @Transactional
    public void save(DataSetColumnPermissionsDTO dto, Long datasetId) {
        DatasetColumnPermission existing = dto.getId() == null ? null : require(dto.getId());
        String type = EeDatapermSupport.normalizeTargetType(dto.getAuthTargetType(), false);
        Long targetId = dto.getAuthTargetId();
        support.checkTarget(datasetId, type, targetId);
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        var duplicate = queryFactory.select(q.id).from(q)
                .where(q.datasetId.eq(datasetId), q.authTargetType.eq(type), q.authTargetId.eq(targetId));
        if (existing != null) {
            duplicate.where(q.id.ne(existing.getId()));
        }
        if (duplicate.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_dataperm_target_exists"));
        }
        String permissions = normalizePermissions(dto.getPermissions());
        // 白名单只对角色规则有意义
        String whiteListUser = DatapermConstants.TARGET_ROLE.equals(type)
                ? EeDatapermSupport.normalizeIdList(dto.getWhiteListUser()) : DatapermConstants.EMPTY_JSON_ARRAY;
        long now = System.currentTimeMillis();
        if (existing == null) {
            DatasetColumnPermission row = new DatasetColumnPermission();
            row.setId(IDUtils.snowID());
            row.setDatasetId(datasetId);
            row.setAuthTargetType(type);
            row.setAuthTargetId(targetId);
            row.setPermissions(permissions);
            row.setWhiteListUser(whiteListUser);
            row.setCreateBy(V3UserUtil.getUid());
            row.setUpdateTime(now);
            repository.save(row);
            return;
        }
        queryFactory.update(q).where(q.id.eq(existing.getId()))
                .set(q.authTargetType, type)
                .set(q.authTargetId, targetId)
                .set(q.permissions, permissions)
                .set(q.whiteListUser, whiteListUser)
                .set(q.updateTime, now)
                .execute();
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public DataSetColumnPermissionsDTO info(Long datasetId, String authTargetType, Long authTargetId) {
        if (datasetId == null || StringUtils.isBlank(authTargetType) || authTargetId == null) {
            return null;
        }
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        DatasetColumnPermission row = queryFactory.selectFrom(q)
                .where(q.datasetId.eq(datasetId), q.authTargetType.eq(authTargetType), q.authTargetId.eq(authTargetId))
                .orderBy(q.updateTime.desc()).fetchFirst();
        return row == null ? null : enrich(List.of(row)).getFirst();
    }

    /**
     * 核心取数链路使用：按数据集、授权对象类型、授权对象查询
     */
    public List<DataSetColumnPermissionsDTO> list(DataSetColumnPermissionsDTO request) {
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        List<BooleanExpression> conditions = new ArrayList<>();
        if (request != null) {
            if (request.getDatasetId() != null) {
                conditions.add(q.datasetId.eq(request.getDatasetId()));
            }
            if (StringUtils.isNotBlank(request.getAuthTargetType())) {
                conditions.add(q.authTargetType.eq(request.getAuthTargetType()));
            }
            if (request.getAuthTargetIds() != null) {
                List<Long> ids = request.getAuthTargetIds().stream().filter(Objects::nonNull).toList();
                if (ids.isEmpty()) {
                    return new ArrayList<>();
                }
                conditions.add(q.authTargetId.in(ids));
            }
            if (request.getAuthTargetId() != null) {
                conditions.add(q.authTargetId.eq(request.getAuthTargetId()));
            }
        }
        List<DatasetColumnPermission> rows = queryFactory.selectFrom(q).where(conditions.toArray(new BooleanExpression[0]))
                .orderBy(q.updateTime.desc(), q.id.desc()).fetch();
        return rows.stream().map(this::toDto).filter(dto -> request == null || request.getEnable() == null
                || Objects.equals(request.getEnable(), dto.getEnable())).toList();
    }

    public List<DatasetColumnPermission> listByDatasets(Collection<Long> datasetIds) {
        if (CollectionUtils.isEmpty(datasetIds)) {
            return new ArrayList<>();
        }
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        return queryFactory.selectFrom(q).where(q.datasetId.in(datasetIds)).orderBy(q.updateTime.desc()).fetch();
    }

    public List<Long> roleTargetsOf(Long datasetId) {
        QDatasetColumnPermission q = QDatasetColumnPermission.datasetColumnPermission;
        return queryFactory.select(q.authTargetId).distinct().from(q)
                .where(q.datasetId.eq(datasetId), q.authTargetType.eq(DatapermConstants.TARGET_ROLE)).fetch();
    }

    DataSetColumnPermissionsDTO toDto(DatasetColumnPermission row) {
        DataSetColumnPermissionsDTO dto = new DataSetColumnPermissionsDTO();
        dto.setId(row.getId());
        dto.setDatasetId(row.getDatasetId());
        dto.setAuthTargetType(row.getAuthTargetType());
        dto.setAuthTargetId(row.getAuthTargetId());
        dto.setPermissions(row.getPermissions());
        dto.setWhiteListUser(EeDatapermSupport.defaultIds(row.getWhiteListUser()));
        dto.setUpdateTime(row.getUpdateTime());
        ColumnPermissions permissions = parse(row.getPermissions());
        dto.setEnable(permissions != null && Boolean.TRUE.equals(permissions.getEnable()));
        return dto;
    }

    private List<DataSetColumnPermissionsDTO> enrich(List<DatasetColumnPermission> rows) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> roleIds = new HashSet<>();
        for (DatasetColumnPermission row : rows) {
            if (DatapermConstants.TARGET_USER.equals(row.getAuthTargetType())) {
                userIds.add(row.getAuthTargetId());
            } else {
                roleIds.add(row.getAuthTargetId());
            }
        }
        Map<Long, String> userNames = support.userNames(userIds);
        Map<Long, String> roleNames = support.roleNames(roleIds);
        List<DataSetColumnPermissionsDTO> result = new ArrayList<>();
        for (DatasetColumnPermission row : rows) {
            DataSetColumnPermissionsDTO dto = toDto(row);
            dto.setDatasetName(support.datasetName(row.getDatasetId()));
            dto.setAuthTargetName(DatapermConstants.TARGET_USER.equals(row.getAuthTargetType())
                    ? userNames.get(row.getAuthTargetId()) : roleNames.get(row.getAuthTargetId()));
            dto.setWhiteListUsers(support.userOptions(EeDatapermSupport.parseIdList(dto.getWhiteListUser())));
            result.add(dto);
        }
        return result;
    }

    static ColumnPermissions parse(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return EeDatapermSupport.MAPPER.readValue(json, ColumnPermissions.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 校验列权限 JSON，补齐核心依赖的非空字段（enable、columns[].id、columns[].selected），保留前端其余属性
     */
    @SuppressWarnings("unchecked")
    private static String normalizePermissions(String json) {
        if (StringUtils.isBlank(json)) {
            DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
        }
        LinkedHashMap<String, Object> root;
        ColumnPermissions typed;
        try {
            root = EeDatapermSupport.MAPPER.readValue(json, MAP_TYPE);
            typed = EeDatapermSupport.MAPPER.readValue(json, ColumnPermissions.class);
        } catch (JsonProcessingException e) {
            DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
            return null;
        }
        if (root.get("enable") == null) {
            root.put("enable", true);
        }
        List<Object> columns = root.get("columns") instanceof List<?> list ? (List<Object>) list : new ArrayList<>();
        List<Object> normalized = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!(columns.get(i) instanceof Map<?, ?> column)) {
                continue;
            }
            ColumnPermissionItem item = typed.getColumns() == null || typed.getColumns().size() <= i ? null : typed.getColumns().get(i);
            if (item == null || item.getId() == null) {
                DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
            }
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) column);
            boolean selected = Boolean.TRUE.equals(item.getSelected());
            copy.put("id", item.getId().toString());
            copy.put("selected", selected);
            if (selected) {
                if (!StringUtils.equalsAny(item.getOpt(), ColumnPermissionConstants.Prohibit, ColumnPermissionConstants.Desensitization)) {
                    DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
                }
                if (ColumnPermissionConstants.Desensitization.equals(item.getOpt())) {
                    checkRule(item.getDesensitizationRule());
                }
            }
            normalized.add(copy);
        }
        root.put("columns", normalized);
        return EeDatapermSupport.toJson(root);
    }

    private static void checkRule(ColumnPermissionItem.DesensitizationRule rule) {
        if (rule == null || rule.getBuiltInRule() == null) {
            DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
        }
        if (rule.getBuiltInRule() != ColumnPermissionItem.BuiltInRule.custom) {
            return;
        }
        Integer m = rule.getM();
        Integer n = rule.getN();
        boolean valid = rule.getCustomBuiltInRule() != null && m != null && n != null;
        if (valid && rule.getCustomBuiltInRule() == ColumnPermissionItem.CustomBuiltInRule.RetainMToN) {
            valid = m >= 1 && n >= m;
        } else if (valid) {
            valid = m >= 0 && n >= 0;
        }
        if (!valid) {
            DEException.throwException(Translator.get("ee_dataperm_permissions_invalid"));
        }
    }
}
