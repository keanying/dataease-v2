package io.dataease.ee.datafilling.manage;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.datafilling.dao.entity.DfForm;
import io.dataease.ee.datafilling.dao.entity.QDfUserTask;
import io.dataease.ee.datafilling.dao.entity.QDfUserTaskItem;
import io.dataease.ee.datafilling.dao.repo.DfFormRepository;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.auth.Perm;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.ResultCode;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据填报资源的访问控制
 */
@Component
public class DfAccess {

    private final EePermissionManage permissionManage;

    private final DfFormRepository formRepository;

    private final JPAQueryFactory queryFactory;

    public DfAccess(EePermissionManage permissionManage, DfFormRepository formRepository, JPAQueryFactory queryFactory) {
        this.permissionManage = permissionManage;
        this.formRepository = formRepository;
        this.queryFactory = queryFactory;
    }

    public DfForm form(Long id) {
        DfForm form = id == null ? null : formRepository.findById(id).orElse(null);
        if (form == null) {
            DEException.throwException(Translator.get("ee_df_form_not_exists"));
        }
        return form;
    }

    public Perm perm(Long resourceId) {
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            return Perm.NONE;
        }
        if (V3UserUtil.isSysAdmin(uid)) {
            return Perm.FULL;
        }
        return permissionManage.effective(uid, V3UserUtil.getOid(), EeConstants.RT_DATA_FILLING, resourceId == null ? 0L : resourceId);
    }

    public boolean can(Long resourceId, int level) {
        return perm(resourceId).satisfies(level);
    }

    public void require(Long resourceId, int level) {
        if (!can(resourceId, level)) {
            deny();
        }
    }

    /**
     * 当前用户是否有该表单的填报任务
     */
    public boolean hasUserTask(Long formId) {
        Long uid = V3UserUtil.getUid();
        if (uid == null || formId == null) {
            return false;
        }
        QDfUserTask q = QDfUserTask.dfUserTask;
        return queryFactory.selectOne().from(q).where(q.uid.eq(uid), q.formId.eq(formId)).fetchFirst() != null;
    }

    /**
     * 查看表单：有查看权限或有该表单的填报任务
     */
    public void requireReadOrFiller(Long formId) {
        if (!can(formId, EeConstants.WEIGHT_READ) && !hasUserTask(formId)) {
            deny();
        }
    }

    /**
     * 当前用户填报任务中可以访问的数据主键
     */
    public boolean ownsRows(Long formId, Collection<String> dataIds) {
        Long uid = V3UserUtil.getUid();
        if (uid == null || CollectionUtils.isEmpty(dataIds)) {
            return false;
        }
        QDfUserTaskItem q = QDfUserTaskItem.dfUserTaskItem;
        List<String> owned = queryFactory.select(q.dataId).from(q)
                .where(q.uid.eq(uid), q.formId.eq(formId), q.dataId.in(dataIds)).fetch();
        Set<String> set = new HashSet<>(owned);
        return set.containsAll(new HashSet<>(dataIds));
    }

    public void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }
}
