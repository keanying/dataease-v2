package io.dataease.ee.dataperm.variable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.variable.dto.SysVariableDto;
import io.dataease.api.permissions.variable.dto.SysVariableValueDto;
import io.dataease.api.permissions.variable.dto.SysVariableValueItem;
import io.dataease.ee.dataperm.DatapermConstants;
import io.dataease.ee.dataperm.dao.entity.*;
import io.dataease.ee.dataperm.dao.repo.SysVariableRepository;
import io.dataease.ee.dataperm.dao.repo.SysVariableUserRepository;
import io.dataease.ee.dataperm.dao.repo.SysVariableValueRepository;
import io.dataease.ee.permissions.user.EeUserVariableSupport;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统变量：变量、变量值以及用户的变量取值。
 *
 * <ul>
 *     <li>文本变量（text）：多个可选值，用户被分配其中若干个；</li>
 *     <li>数值变量（num）：一条取值范围（begin/end），用户被分配一个整数；</li>
 *     <li>时间变量（time）：一条时间范围（yyyy-MM-dd HH:mm:ss），用户被分配一个时间。</li>
 * </ul>
 * 同时实现用户模块的 {@link EeUserVariableSupport} 扩展点，供用户表单读写用户的变量取值。
 */
@Component
public class EeSysVariableManage implements EeUserVariableSupport {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(DatapermConstants.TIME_PATTERN);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JPAQueryFactory queryFactory;

    private final SysVariableRepository variableRepository;

    private final SysVariableValueRepository valueRepository;

    private final SysVariableUserRepository userRepository;

    public EeSysVariableManage(JPAQueryFactory queryFactory, SysVariableRepository variableRepository,
                               SysVariableValueRepository valueRepository, SysVariableUserRepository userRepository) {
        this.queryFactory = queryFactory;
        this.variableRepository = variableRepository;
        this.valueRepository = valueRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ 变量

    public List<SysVariableDto> list() {
        QSysVariable q = QSysVariable.sysVariable;
        List<SysVariable> variables = queryFactory.selectFrom(q).orderBy(q.createTime.asc(), q.id.asc()).fetch();
        Map<Long, List<SysVariableValue>> values = valuesOf(variables.stream().map(SysVariable::getId).toList());
        return variables.stream().map(variable -> toDto(variable, values.getOrDefault(variable.getId(), List.of()))).toList();
    }

    public SysVariableDto detail(Long id) {
        SysVariable variable = require(id);
        return toDto(variable, values(variable.getId()));
    }

    @Transactional
    public SysVariableDto create(SysVariableDto dto) {
        String name = checkName(dto.getName(), null);
        String type = checkType(dto.getType());
        long now = System.currentTimeMillis();
        SysVariable variable = new SysVariable();
        variable.setId(IDUtils.snowID());
        variable.setName(name);
        variable.setType(type);
        variable.setCreateBy(V3UserUtil.getUid());
        variable.setCreateTime(now);
        variable.setUpdateTime(now);
        variableRepository.save(variable);
        return toDto(variable, List.of());
    }

    @Transactional
    public SysVariableDto edit(SysVariableDto dto) {
        SysVariable variable = require(dto.getId());
        String name = checkName(dto.getName(), variable.getId());
        String type = checkType(dto.getType());
        if (!StringUtils.equals(type, variable.getType())) {
            // 修改类型后，原有变量值及用户的取值全部失效
            clearValues(variable.getId());
        }
        QSysVariable q = QSysVariable.sysVariable;
        queryFactory.update(q).where(q.id.eq(variable.getId()))
                .set(q.name, name).set(q.type, type).set(q.updateTime, System.currentTimeMillis()).execute();
        variable.setName(name);
        variable.setType(type);
        return toDto(variable, values(variable.getId()));
    }

    @Transactional
    public void delete(Long id) {
        SysVariable variable = require(id);
        clearValues(variable.getId());
        variableRepository.deleteById(variable.getId());
    }

    private void clearValues(Long variableId) {
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        queryFactory.delete(v).where(v.sysVariableId.eq(variableId)).execute();
        QSysVariableUser u = QSysVariableUser.sysVariableUser;
        queryFactory.delete(u).where(u.sysVariableId.eq(variableId)).execute();
    }

    public SysVariable get(Long id) {
        return id == null ? null : variableRepository.findById(id).orElse(null);
    }

    public SysVariable require(Long id) {
        SysVariable variable = get(id);
        if (variable == null) {
            DEException.throwException(Translator.get("ee_dataperm_var_not_exist"));
        }
        return variable;
    }

    private String checkName(String raw, Long selfId) {
        String name = StringUtils.trim(raw);
        if (StringUtils.isEmpty(name) || name.length() > DatapermConstants.NAME_MAX) {
            DEException.throwException(Translator.get("ee_dataperm_var_name_invalid"));
        }
        QSysVariable q = QSysVariable.sysVariable;
        var query = queryFactory.select(q.id).from(q).where(q.name.eq(name));
        if (selfId != null) {
            query.where(q.id.ne(selfId));
        }
        if (query.fetchFirst() != null) {
            DEException.throwException(Translator.get("ee_dataperm_var_name_exists"));
        }
        return name;
    }

    private String checkType(String raw) {
        String type = StringUtils.trimToEmpty(raw);
        if (!DatapermConstants.VAR_TYPES.contains(type)) {
            DEException.throwException(Translator.get("ee_dataperm_var_type_invalid"));
        }
        return type;
    }

    private SysVariableDto toDto(SysVariable variable, List<SysVariableValue> values) {
        SysVariableDto dto = new SysVariableDto();
        dto.setId(variable.getId());
        dto.setName(variable.getName());
        dto.setType(variable.getType());
        dto.setRoot(false);
        dto.setDisabled(false);
        SysVariableValue range = values.isEmpty() ? null : values.getFirst();
        if (range != null && DatapermConstants.VAR_NUM.equals(variable.getType())) {
            dto.setMin(toLong(range.getBegin(), RoundingMode.CEILING));
            dto.setMax(toLong(range.getEnd(), RoundingMode.FLOOR));
        }
        if (range != null && DatapermConstants.VAR_TIME.equals(variable.getType())) {
            dto.setStartTime(StringUtils.defaultString(range.getBegin()));
            dto.setEndTime(StringUtils.defaultString(range.getEnd()));
        }
        return dto;
    }

    private static Long toLong(String text, RoundingMode mode) {
        BigDecimal number = toNumber(text);
        return number == null ? null : number.setScale(0, mode).longValue();
    }

    private static BigDecimal toNumber(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime toTime(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 变量值

    public List<SysVariableValue> values(Long variableId) {
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        return queryFactory.selectFrom(v).where(v.sysVariableId.eq(variableId)).orderBy(v.createTime.asc(), v.id.asc()).fetch();
    }

    private Map<Long, List<SysVariableValue>> valuesOf(Collection<Long> variableIds) {
        if (CollectionUtils.isEmpty(variableIds)) {
            return new HashMap<>();
        }
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        return queryFactory.selectFrom(v).where(v.sysVariableId.in(variableIds)).orderBy(v.createTime.asc(), v.id.asc()).fetch()
                .stream().collect(Collectors.groupingBy(SysVariableValue::getSysVariableId, LinkedHashMap::new, Collectors.toList()));
    }

    public List<SysVariableValueDto> valueDtos(Long variableId) {
        return values(variableId).stream().map(EeSysVariableManage::toValueDto).toList();
    }

    public PageResult<SysVariableValueDto> valuePage(int goPage, int pageSize, Long variableId, String keyword) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        PageRequest pageable = PageRequest.of(page - 1, size);
        if (variableId == null) {
            return new PageResult<>(new ArrayList<>(), 0L, pageable);
        }
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        var query = queryFactory.selectFrom(v).where(v.sysVariableId.eq(variableId));
        if (StringUtils.isNotBlank(keyword)) {
            query.where(v.value.contains(keyword.trim()));
        }
        long total = query.clone().fetchCount();
        List<SysVariableValueDto> records = query.orderBy(v.createTime.asc(), v.id.asc())
                .offset((long) (page - 1) * size).limit(size).fetch()
                .stream().map(EeSysVariableManage::toValueDto).toList();
        return new PageResult<>(new ArrayList<>(records), total, pageable);
    }

    @Transactional
    public SysVariableValueDto createValue(SysVariableValueDto dto) {
        SysVariable variable = require(dto.getSysVariableId());
        if (!DatapermConstants.VAR_TEXT.equals(variable.getType())) {
            // 数值、时间变量只保存一条取值范围
            List<SysVariableValue> existing = values(variable.getId());
            if (!existing.isEmpty()) {
                dto.setId(existing.getFirst().getId());
                return editValue(dto);
            }
        }
        SysVariableValue value = new SysVariableValue();
        value.setId(IDUtils.snowID());
        value.setSysVariableId(variable.getId());
        value.setCreateTime(System.currentTimeMillis());
        fillValue(variable, value, dto);
        valueRepository.save(value);
        return toValueDto(value);
    }

    @Transactional
    public SysVariableValueDto editValue(SysVariableValueDto dto) {
        SysVariableValue value = dto.getId() == null ? null : valueRepository.findById(dto.getId()).orElse(null);
        if (value == null) {
            DEException.throwException(Translator.get("ee_dataperm_value_not_exist"));
        }
        SysVariable variable = require(value.getSysVariableId());
        fillValue(variable, value, dto);
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        var update = queryFactory.update(v).where(v.id.eq(value.getId()));
        setNullable(update, v.value, value.getValue());
        setNullable(update, v.valueDesc, value.getValueDesc());
        setNullable(update, v.begin, value.getBegin());
        setNullable(update, v.end, value.getEnd());
        update.execute();
        return toValueDto(value);
    }

    private static void setNullable(com.querydsl.jpa.impl.JPAUpdateClause update, com.querydsl.core.types.dsl.StringPath path, String value) {
        if (value == null) {
            update.setNull(path);
        } else {
            update.set(path, value);
        }
    }

    private void fillValue(SysVariable variable, SysVariableValue value, SysVariableValueDto dto) {
        if (DatapermConstants.VAR_TEXT.equals(variable.getType())) {
            String text = StringUtils.trim(dto.getValue());
            if (StringUtils.isEmpty(text) || text.length() > DatapermConstants.VALUE_MAX) {
                DEException.throwException(Translator.get("ee_dataperm_value_invalid"));
            }
            String desc = StringUtils.trimToNull(dto.getValueDesc());
            if (desc != null && desc.length() > DatapermConstants.VALUE_MAX) {
                DEException.throwException(Translator.get("ee_dataperm_value_desc_invalid"));
            }
            QSysVariableValue v = QSysVariableValue.sysVariableValue;
            var duplicate = queryFactory.select(v.id).from(v).where(v.sysVariableId.eq(variable.getId()), v.value.eq(text));
            if (value.getId() != null) {
                duplicate.where(v.id.ne(value.getId()));
            }
            if (duplicate.fetchFirst() != null) {
                DEException.throwException(Translator.get("ee_dataperm_value_exists"));
            }
            value.setValue(text);
            value.setValueDesc(desc);
            value.setBegin(null);
            value.setEnd(null);
            return;
        }
        String begin = StringUtils.trimToNull(dto.getBegin());
        String end = StringUtils.trimToNull(dto.getEnd());
        if (DatapermConstants.VAR_NUM.equals(variable.getType())) {
            BigDecimal b = toNumber(begin);
            BigDecimal e = toNumber(end);
            if ((begin != null && b == null) || (end != null && e == null) || (b != null && e != null && b.compareTo(e) > 0)) {
                DEException.throwException(Translator.get("ee_dataperm_range_invalid"));
            }
        } else {
            LocalDateTime b = toTime(begin);
            LocalDateTime e = toTime(end);
            if ((begin != null && b == null) || (end != null && e == null) || (b != null && e != null && b.isAfter(e))) {
                DEException.throwException(Translator.get("ee_dataperm_range_invalid"));
            }
        }
        value.setValue(null);
        value.setValueDesc(null);
        value.setBegin(begin);
        value.setEnd(end);
    }

    @Transactional
    public void deleteValues(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        QSysVariableValue v = QSysVariableValue.sysVariableValue;
        queryFactory.delete(v).where(v.id.in(ids)).execute();
    }

    private static SysVariableValueDto toValueDto(SysVariableValue value) {
        SysVariableValueDto dto = new SysVariableValueDto();
        dto.setId(value.getId());
        dto.setSysVariableId(value.getSysVariableId());
        dto.setValue(value.getValue());
        dto.setValueDesc(value.getValueDesc());
        dto.setBegin(value.getBegin());
        dto.setEnd(value.getEnd());
        return dto;
    }

    // ------------------------------------------------------------------ 用户的变量取值

    private List<SysVariableUser> userRows(Collection<Long> uids) {
        if (CollectionUtils.isEmpty(uids)) {
            return new ArrayList<>();
        }
        QSysVariableUser u = QSysVariableUser.sysVariableUser;
        return queryFactory.selectFrom(u).where(u.userId.in(uids)).orderBy(u.id.asc()).fetch();
    }

    /**
     * 用户的系统变量（用户表单、行权限与 SQL 数据集的变量替换均使用此结构）
     */
    @Override
    public List<SysVariableValueItem> userVariables(Long uid) {
        if (uid == null) {
            return new ArrayList<>();
        }
        return userVariables(List.of(uid)).getOrDefault(uid, new ArrayList<>());
    }

    public Map<Long, List<SysVariableValueItem>> userVariables(Collection<Long> uids) {
        Map<Long, List<SysVariableValueItem>> result = new HashMap<>();
        List<SysVariableUser> rows = userRows(uids);
        if (rows.isEmpty()) {
            return result;
        }
        Set<Long> variableIds = rows.stream().map(SysVariableUser::getSysVariableId).collect(Collectors.toSet());
        Map<Long, SysVariable> variables = new HashMap<>();
        variableRepository.findAllById(variableIds).forEach(variable -> variables.put(variable.getId(), variable));
        Map<Long, List<SysVariableValue>> values = valuesOf(variableIds);
        for (SysVariableUser row : rows) {
            SysVariable variable = variables.get(row.getSysVariableId());
            if (variable == null) {
                continue;
            }
            List<SysVariableValue> variableValues = values.getOrDefault(variable.getId(), List.of());
            SysVariableValueItem item = new SysVariableValueItem();
            item.setVariableId(variable.getId());
            item.setVariableName(variable.getName());
            item.setVariableType(variable.getType());
            item.setSysVariableDto(toDto(variable, variableValues));
            item.setValueList(new ArrayList<>(variableValues.stream().map(EeSysVariableManage::toValueDto).toList()));
            item.setValid(true);
            if (DatapermConstants.VAR_TEXT.equals(variable.getType())) {
                Set<String> existing = variableValues.stream().map(value -> value.getId().toString()).collect(Collectors.toSet());
                List<String> ids = parseIds(row.getVariableValueIds()).stream().filter(existing::contains).toList();
                item.setVariableValueIds(new ArrayList<>(ids));
                item.setVariableValueId(ids.isEmpty() ? null : Long.valueOf(ids.getFirst()));
            } else {
                item.setVariableValue(row.getVariableValue());
            }
            result.computeIfAbsent(row.getUserId(), key -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private static List<String> parseIds(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        List<String> ids = JsonUtil.parseList(json, STRING_LIST);
        return ids == null ? new ArrayList<>() : ids.stream().filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
    }

    /**
     * 整体替换用户的变量取值
     */
    @Override
    @Transactional
    public void saveUserVariables(Long uid, List<SysVariableValueItem> items) {
        if (uid == null) {
            return;
        }
        List<SysVariableUser> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (SysVariableValueItem item : items == null ? List.<SysVariableValueItem>of() : items) {
            if (item == null || item.getVariableId() == null || !seen.add(item.getVariableId())) {
                continue;
            }
            SysVariable variable = get(item.getVariableId());
            if (variable == null) {
                continue;
            }
            SysVariableUser row = new SysVariableUser();
            row.setId(IDUtils.snowID());
            row.setUserId(uid);
            row.setSysVariableId(variable.getId());
            row.setUpdateTime(now);
            List<SysVariableValue> variableValues = values(variable.getId());
            if (DatapermConstants.VAR_TEXT.equals(variable.getType())) {
                Set<String> existing = variableValues.stream().map(value -> value.getId().toString()).collect(Collectors.toSet());
                List<String> selected = new ArrayList<>();
                if (item.getVariableValueIds() != null) {
                    selected.addAll(item.getVariableValueIds());
                }
                if (selected.isEmpty() && item.getVariableValueId() != null) {
                    selected.add(item.getVariableValueId().toString());
                }
                List<String> ids = selected.stream().filter(Objects::nonNull).map(String::trim).filter(existing::contains).distinct().toList();
                if (ids.isEmpty()) {
                    continue;
                }
                row.setVariableValueIds(JsonUtil.toJSONString(ids).toString());
            } else {
                String value = StringUtils.trimToNull(item.getVariableValue());
                if (value == null) {
                    continue;
                }
                checkUserValue(variable, value, variableValues.isEmpty() ? null : variableValues.getFirst());
                row.setVariableValue(value);
            }
            rows.add(row);
        }
        QSysVariableUser u = QSysVariableUser.sysVariableUser;
        queryFactory.delete(u).where(u.userId.eq(uid)).execute();
        if (!rows.isEmpty()) {
            userRepository.saveAll(rows);
        }
    }

    private void checkUserValue(SysVariable variable, String value, SysVariableValue range) {
        if (DatapermConstants.VAR_NUM.equals(variable.getType())) {
            if (!value.matches("^[-+]?\\d+$")) {
                DEException.throwException(Translator.get("ee_dataperm_user_var_invalid", variable.getName()));
            }
            BigDecimal number = new BigDecimal(value);
            BigDecimal min = range == null ? null : toNumber(range.getBegin());
            BigDecimal max = range == null ? null : toNumber(range.getEnd());
            if ((min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0)) {
                DEException.throwException(Translator.get("ee_dataperm_user_var_out_of_range", variable.getName()));
            }
            return;
        }
        LocalDateTime time = toTime(value);
        if (time == null) {
            DEException.throwException(Translator.get("ee_dataperm_user_var_invalid", variable.getName()));
        }
        LocalDateTime start = range == null ? null : toTime(range.getBegin());
        LocalDateTime end = range == null ? null : toTime(range.getEnd());
        if ((start != null && time.isBefore(start)) || (end != null && time.isAfter(end))) {
            DEException.throwException(Translator.get("ee_dataperm_user_var_out_of_range", variable.getName()));
        }
    }

    /**
     * 用户列表中展示的摘要，如“部门:研发,测试;级别:3”
     */
    @Override
    public String summary(Long uid) {
        List<SysVariableValueItem> items = userVariables(uid);
        if (items.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (SysVariableValueItem item : items) {
            String text = String.join(",", displayValues(item));
            if (StringUtils.isNotEmpty(text)) {
                parts.add(item.getVariableName() + ":" + text);
            }
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    private static List<String> displayValues(SysVariableValueItem item) {
        if (DatapermConstants.VAR_TEXT.equals(item.getVariableType())) {
            List<SysVariableValueDto> valueList = item.getValueList() == null ? List.of() : item.getValueList();
            return valueList.stream().filter(value -> item.getVariableValueIds().contains(value.getId().toString()))
                    .map(SysVariableValueDto::getValue).toList();
        }
        return StringUtils.isEmpty(item.getVariableValue()) ? List.of() : List.of(item.getVariableValue());
    }

    /**
     * 删除用户时清理其变量取值，以及以该用户为授权对象的行列权限规则
     */
    @Override
    @Transactional
    public void deleteUser(Long uid) {
        if (uid == null) {
            return;
        }
        QSysVariableUser u = QSysVariableUser.sysVariableUser;
        queryFactory.delete(u).where(u.userId.eq(uid)).execute();
        QDatasetRowPermission r = QDatasetRowPermission.datasetRowPermission;
        queryFactory.delete(r).where(r.authTargetType.eq(DatapermConstants.TARGET_USER), r.authTargetId.eq(uid)).execute();
        QDatasetColumnPermission c = QDatasetColumnPermission.datasetColumnPermission;
        queryFactory.delete(c).where(c.authTargetType.eq(DatapermConstants.TARGET_USER), c.authTargetId.eq(uid)).execute();
    }

    /**
     * 批量查询用户的变量取值：uid -> (变量ID 或内置变量名 sysParams.xxx -> 值，文本变量多个值以逗号分隔)
     */
    public Map<Long, Map<String, String>> batchValues(List<Long> uids, Map<Long, Map<String, String>> builtins) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        if (CollectionUtils.isEmpty(uids)) {
            return result;
        }
        Map<Long, List<SysVariableValueItem>> variables = userVariables(uids);
        for (Long uid : uids) {
            Map<String, String> values = new LinkedHashMap<>();
            if (builtins != null && builtins.get(uid) != null) {
                values.putAll(builtins.get(uid));
            }
            for (SysVariableValueItem item : variables.getOrDefault(uid, List.of())) {
                List<String> display = displayValues(item);
                if (!display.isEmpty()) {
                    values.put(item.getVariableId().toString(), String.join(",", display));
                }
            }
            result.put(uid, values);
        }
        return result;
    }
}
