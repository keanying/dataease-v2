package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerAuth;
import io.dataease.ee.permissions.dao.entity.QPerAuth;
import io.dataease.ee.permissions.dao.repo.PerAuthRepository;
import io.dataease.utils.IDUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 授权记录的增删改。
 */
@Component
public class EeGrantManage {

    private final JPAQueryFactory queryFactory;

    private final PerAuthRepository perAuthRepository;

    public EeGrantManage(JPAQueryFactory queryFactory, PerAuthRepository perAuthRepository) {
        this.queryFactory = queryFactory;
        this.perAuthRepository = perAuthRepository;
    }

    /**
     * 设置授权；weight 为 0 时删除授权记录
     */
    @Transactional
    public void save(int subjectType, Long subjectId, int rtId, Long resourceId, int weight, int ext, Long oid) {
        QPerAuth q = QPerAuth.perAuth;
        PerAuth exist = queryFactory.selectFrom(q).where(q.subjectType.eq(subjectType), q.subjectId.eq(subjectId),
                q.rtId.eq(rtId), q.resourceId.eq(resourceId)).fetchFirst();
        if (weight <= 0) {
            if (exist != null) {
                perAuthRepository.deleteById(exist.getId());
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (exist != null) {
            var update = queryFactory.update(q).set(q.weight, weight).set(q.ext, ext).set(q.updateTime, now)
                    .where(q.id.eq(exist.getId()));
            if (oid != null) {
                // 用户可属于多个组织：同一主体对同一资源只有一条授权（唯一约束），以最近一次授权所在组织为准
                update.set(q.oid, oid);
            }
            update.execute();
            return;
        }
        PerAuth auth = new PerAuth();
        auth.setId(IDUtils.snowID());
        auth.setSubjectType(subjectType);
        auth.setSubjectId(subjectId);
        auth.setRtId(rtId);
        auth.setResourceId(resourceId);
        auth.setWeight(weight);
        auth.setExt(ext);
        auth.setOid(oid);
        auth.setUpdateTime(now);
        perAuthRepository.save(auth);
    }

    /**
     * 新建资源时：创建者获得全部权限，同时继承上级目录的授权
     */
    @Transactional
    public void onResourceCreated(int rtId, Long resourceId, Long pid, Long creator, Long oid, boolean creatorIsAdmin) {
        if (pid != null && pid > 0) {
            QPerAuth q = QPerAuth.perAuth;
            List<PerAuth> parentGrants = queryFactory.selectFrom(q).where(q.rtId.eq(rtId), q.resourceId.eq(pid)).fetch();
            for (PerAuth grant : parentGrants) {
                save(grant.getSubjectType(), grant.getSubjectId(), rtId, resourceId, grant.getWeight(), grant.getExt(), grant.getOid());
            }
        }
        if (creator != null && !creatorIsAdmin) {
            save(EeConstants.SUBJECT_USER, creator, rtId, resourceId, EeConstants.WEIGHT_AUTH, EeConstants.EXT_ALL, oid);
        }
    }

    @Transactional
    public void deleteByResources(Collection<Long> resourceIds) {
        if (CollectionUtils.isEmpty(resourceIds)) {
            return;
        }
        QPerAuth q = QPerAuth.perAuth;
        queryFactory.delete(q).where(q.resourceId.in(resourceIds), q.rtId.ne(EeConstants.RT_MENU)).execute();
    }

    @Transactional
    public void deleteBySubject(int subjectType, Long subjectId) {
        QPerAuth q = QPerAuth.perAuth;
        queryFactory.delete(q).where(q.subjectType.eq(subjectType), q.subjectId.eq(subjectId)).execute();
    }

    @Transactional
    public void deleteBySubjectInOrg(int subjectType, Long subjectId, Long oid) {
        QPerAuth q = QPerAuth.perAuth;
        queryFactory.delete(q).where(q.subjectType.eq(subjectType), q.subjectId.eq(subjectId), q.oid.eq(oid)).execute();
    }

    @Transactional
    public void deleteByOrg(Long oid) {
        QPerAuth q = QPerAuth.perAuth;
        queryFactory.delete(q).where(q.oid.eq(oid)).execute();
    }
}
