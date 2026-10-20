package io.dataease.ee.permissions.auth;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.BusiResourceEnum;
import io.dataease.dao.auto.entity.QCoreChartView;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.interact.EeExtension;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.permission.util.V3UserUtil;

import java.util.List;
import java.util.Map;

/**
 * 核心业务（取数、预览等）的资源鉴权。
 *
 * <p>数据集查看权限：直接拥有数据集权限，或可以查看引用了该数据集的仪表板/数据大屏。</p>
 */
@EeExtension("corePermissionManage")
public class CorePermissionExtension {

    private final EePermissionManage permissionManage;

    private final EeMenuManage menuManage;

    private final JPAQueryFactory queryFactory;

    public CorePermissionExtension(EePermissionManage permissionManage, EeMenuManage menuManage, JPAQueryFactory queryFactory) {
        this.permissionManage = permissionManage;
        this.menuManage = menuManage;
        this.queryFactory = queryFactory;
    }

    public boolean checkAuth(BusiPerCheckDTO dto) {
        if (dto == null || dto.getId() == null || dto.getBusiEnum() == null) {
            return false;
        }
        Long uid = V3UserUtil.getUid();
        if (uid == null) {
            return false;
        }
        if (permissionManage.isSysAdmin(uid)) {
            return true;
        }
        int level = dto.getAuthEnum() == null ? AuthEnum.READ.getWeight() : dto.getAuthEnum().getWeight();
        if (dto.getBusiEnum() == BusiResourceEnum.MENU) {
            return menuManage.canAccess(dto.getId());
        }
        Long oid = V3UserUtil.getOid();
        int rtId = dto.getBusiEnum().getFlag();
        if (permissionManage.effective(uid, oid, rtId, dto.getId()).satisfies(level)) {
            return true;
        }
        if (rtId == EeConstants.RT_DATASET && level <= EeConstants.WEIGHT_READ) {
            return readableThroughVisualization(uid, oid, dto.getId());
        }
        return false;
    }

    private boolean readableThroughVisualization(Long uid, Long oid, Long datasetId) {
        QCoreChartView view = QCoreChartView.coreChartView;
        List<Long> sceneIds = queryFactory.select(view.sceneId).distinct().from(view).where(view.tableId.eq(datasetId)).fetch();
        if (sceneIds.isEmpty()) {
            return false;
        }
        Map<Long, Perm> panels = permissionManage.effective(uid, oid, EeConstants.RT_PANEL);
        Map<Long, Perm> screens = permissionManage.effective(uid, oid, EeConstants.RT_SCREEN);
        return sceneIds.stream().anyMatch(id -> panels.containsKey(id) || screens.containsKey(id));
    }
}
