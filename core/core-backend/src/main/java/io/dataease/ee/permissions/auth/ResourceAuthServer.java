package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.auth.api.ResourceAuthApi;
import io.dataease.api.permissions.auth.dto.ResourcePermissionRequest;
import io.dataease.api.permissions.auth.dto.SubjectByResourceRequest;
import io.dataease.api.permissions.auth.vo.ResourcePermissionVO;
import io.dataease.api.permissions.auth.vo.SubjectVO;
import io.dataease.api.permissions.user.vo.UserReciVO;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源授权查询：按资源查有权限的用户、当前组织全部主体
 */
@RestController("resourceAuthServer")
@RequestMapping("/resourceAuth")
public class ResourceAuthServer implements ResourceAuthApi {

    private final JPAQueryFactory queryFactory;

    private final EePermissionManage permissionManage;

    private final EeBusiResourceManage busiResourceManage;

    private final EeRoleManage roleManage;

    public ResourceAuthServer(JPAQueryFactory queryFactory, EePermissionManage permissionManage, EeBusiResourceManage busiResourceManage,
                              EeRoleManage roleManage) {
        this.queryFactory = queryFactory;
        this.permissionManage = permissionManage;
        this.busiResourceManage = busiResourceManage;
        this.roleManage = roleManage;
    }

    @Override
    public List<ResourcePermissionVO> queryResourcePermission(ResourcePermissionRequest request) {
        List<ResourcePermissionVO> result = new ArrayList<>();
        if (request == null || CollectionUtils.isEmpty(request.getResourceIds()) || CollectionUtils.isEmpty(request.getTargets())) {
            return result;
        }
        int rtId = request.getResourceType() == null ? EeConstants.RT_PANEL : request.getResourceType();
        for (Long resourceId : request.getResourceIds()) {
            Long oid = busiResourceManage.orgOf(resourceId);
            for (UserReciVO target : request.getTargets()) {
                ResourcePermissionVO vo = new ResourcePermissionVO();
                vo.setUserId(target.getUserId());
                vo.setRoleIds(target.getRoleIds());
                vo.setHasRootRole(target.isHasRootRole());
                vo.setCommonRoleIds(target.getCommonRoleIds());
                vo.setResourceId(resourceId);
                vo.setEnable(oid != null && permissionManage.effective(target.getUserId(), oid, rtId, resourceId).satisfies(EeConstants.WEIGHT_READ));
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    public List<Long> queryAuthorizedUserIds(SubjectByResourceRequest request) {
        List<Long> result = new ArrayList<>();
        if (request == null || request.getResourceId() == null) {
            return result;
        }
        PerBusiResource resource = busiResourceManage.get(request.getResourceId());
        if (resource == null) {
            return result;
        }
        int rtId = request.getResourceFlag() == null ? resource.getRtId() : request.getResourceFlag();
        QPerUser q = QPerUser.perUser;
        for (PerUser user : queryFactory.selectFrom(q).where(q.defaultOid.eq(resource.getOrgId()), q.enable.isTrue()).fetch()) {
            if (permissionManage.effective(user.getId(), resource.getOrgId(), rtId, resource.getId()).satisfies(EeConstants.WEIGHT_READ)) {
                result.add(user.getId());
            }
        }
        return result;
    }

    @Override
    public List<SubjectVO> queryAllSubjects(Integer type) {
        List<SubjectVO> result = new ArrayList<>();
        Long oid = V3UserUtil.getOid();
        if (oid == null) {
            return result;
        }
        if (type != null && type == EeConstants.SUBJECT_ROLE) {
            for (PerRole role : roleManage.rolesOfOrg(oid)) {
                result.add(new SubjectVO(role.getId(), EeConstants.SUBJECT_ROLE, role.getName(), null));
            }
            return result;
        }
        QPerUser q = QPerUser.perUser;
        for (PerUser user : queryFactory.selectFrom(q).where(q.defaultOid.eq(oid)).orderBy(q.createTime.desc()).fetch()) {
            result.add(new SubjectVO(user.getId(), EeConstants.SUBJECT_USER, user.getName(), user.getAccount()));
        }
        return result;
    }
}
