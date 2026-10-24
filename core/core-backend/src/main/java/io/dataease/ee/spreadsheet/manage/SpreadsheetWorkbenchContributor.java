package io.dataease.ee.spreadsheet.manage;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.visualization.request.VisualizationWorkbranchQueryRequest;
import io.dataease.api.visualization.vo.VisualizationResourceVO;
import io.dataease.api.visualization.vo.VisualizationStoreVO;
import io.dataease.constant.BusiResourceEnum;
import io.dataease.ee.permissions.resource.EeWorkbenchContributor;
import io.dataease.ee.spreadsheet.SpreadsheetConstants;
import io.dataease.ee.spreadsheet.dao.QXpackSpreadsheet;
import io.dataease.operation.dao.auto.entity.QCoreOptRecent;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.visualization.dao.auto.entity.QCoreStore;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工作台“最近使用 / 我的收藏”中的电子表格记录（权限过滤由 PerFilterExtension 统一完成）
 */
@Component
public class SpreadsheetWorkbenchContributor implements EeWorkbenchContributor {

    private static final String TYPE = "spreadsheet";

    private final JPAQueryFactory queryFactory;

    public SpreadsheetWorkbenchContributor(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    private boolean typeMatches(VisualizationWorkbranchQueryRequest request) {
        return StringUtils.isBlank(request.getType()) || StringUtils.equalsIgnoreCase(request.getType(), TYPE);
    }

    private BooleanExpression baseCondition(QXpackSpreadsheet s, VisualizationWorkbranchQueryRequest request) {
        BooleanExpression condition = s.nodeType.eq(SpreadsheetConstants.NODE_SHEET).and(s.orgId.eq(V3UserUtil.getOid()));
        if (StringUtils.isNotBlank(request.getKeyword())) {
            condition = condition.and(s.name.lower().like("%" + request.getKeyword().toLowerCase() + "%"));
        }
        return condition;
    }

    @Override
    public List<VisualizationResourceVO> recent(VisualizationWorkbranchQueryRequest request, int limit) {
        Long uid = V3UserUtil.getUid();
        if (uid == null || V3UserUtil.getOid() == null || !typeMatches(request)) {
            return Collections.emptyList();
        }
        QXpackSpreadsheet s = QXpackSpreadsheet.xpackSpreadsheet;
        QCoreOptRecent recent = QCoreOptRecent.coreOptRecent;
        QCoreStore store = QCoreStore.coreStore;
        JPAQuery<Tuple> query = queryFactory.select(s.id, s.name, s.createBy, s.updateBy, s.updateTime, s.status, store.id)
                .from(s)
                .innerJoin(recent).on(recent.resourceId.eq(s.id).and(recent.uid.eq(uid))
                        .and(recent.resourceType.eq(BusiResourceEnum.SPREADSHEET.getFlag())))
                .leftJoin(store).on(store.resourceId.eq(s.id).and(store.uid.eq(uid)))
                .where(baseCondition(s, request))
                .orderBy(request.isAsc() ? s.updateTime.asc() : s.updateTime.desc())
                .limit(limit);
        List<VisualizationResourceVO> result = new ArrayList<>();
        for (Tuple row : query.fetch()) {
            Long id = row.get(s.id);
            result.add(new VisualizationResourceVO(id, id, row.get(s.name), TYPE,
                    userText(row.get(s.createBy)), userText(row.get(s.updateBy)), row.get(s.updateTime),
                    row.get(store.id) != null, 9, 0, ObjectUtils.defaultIfNull(row.get(s.status), 0)));
        }
        return result;
    }

    @Override
    public List<VisualizationStoreVO> store(VisualizationWorkbranchQueryRequest request, int limit) {
        Long uid = V3UserUtil.getUid();
        if (uid == null || V3UserUtil.getOid() == null || !typeMatches(request)) {
            return Collections.emptyList();
        }
        QXpackSpreadsheet s = QXpackSpreadsheet.xpackSpreadsheet;
        QCoreStore store = QCoreStore.coreStore;
        JPAQuery<Tuple> query = queryFactory.select(store.id, s.id, s.name, s.createBy, s.updateBy, s.updateTime, s.status)
                .from(store)
                .innerJoin(s).on(store.resourceId.eq(s.id))
                .where(store.uid.eq(uid), store.resourceType.eq(BusiResourceEnum.SPREADSHEET.getFlag()), baseCondition(s, request))
                .orderBy(request.isAsc() ? s.updateTime.asc() : s.updateTime.desc())
                .limit(limit);
        List<VisualizationStoreVO> result = new ArrayList<>();
        for (Tuple row : query.fetch()) {
            result.add(new VisualizationStoreVO(row.get(store.id), row.get(s.id), row.get(s.name), TYPE,
                    userText(row.get(s.createBy)), userText(row.get(s.updateBy)), row.get(s.updateTime),
                    9, 0, ObjectUtils.defaultIfNull(row.get(s.status), 0)));
        }
        return result;
    }

    private static String userText(Long uid) {
        return uid == null ? null : String.valueOf(uid);
    }
}
