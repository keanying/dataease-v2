package io.dataease.ee.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.dao.auto.entity.*;
import io.dataease.ee.auth.PasswordUtils;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.dao.entity.PerOrg;
import io.dataease.ee.permissions.dao.entity.PerRole;
import io.dataease.ee.permissions.dao.entity.PerUser;
import io.dataease.ee.permissions.dao.entity.PerUserRole;
import io.dataease.ee.permissions.dao.repo.PerOrgRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRepository;
import io.dataease.ee.permissions.dao.repo.PerUserRoleRepository;
import io.dataease.ee.permissions.resource.EeBusiResourceManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.i18n.Translator;
import io.dataease.initSql.Version;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.Map;

/**
 * 企业版初始数据：默认组织、内置角色、系统管理员，并把已有资源登记到默认组织。
 */
@Component
public class EeDataInit implements EeSqlBlock {

    private final PerOrgRepository perOrgRepository;

    private final PerUserRepository perUserRepository;

    private final PerUserRoleRepository perUserRoleRepository;

    private final EeRoleManage roleManage;

    private final EeBusiResourceManage busiResourceManage;

    private final JPAQueryFactory queryFactory;

    @Value("${dataease.default-pwd:}")
    private String configuredPwd;

    @Value("${dataease.path.substitule:}")
    private String substitutePath;

    public EeDataInit(PerOrgRepository perOrgRepository, PerUserRepository perUserRepository, PerUserRoleRepository perUserRoleRepository,
                      EeRoleManage roleManage, EeBusiResourceManage busiResourceManage, JPAQueryFactory queryFactory) {
        this.perOrgRepository = perOrgRepository;
        this.perUserRepository = perUserRepository;
        this.perUserRoleRepository = perUserRoleRepository;
        this.roleManage = roleManage;
        this.busiResourceManage = busiResourceManage;
        this.queryFactory = queryFactory;
    }

    @Override
    public Version getVersion() {
        return new Version("3.1.0");
    }

    @Override
    @Transactional
    public void execute() {
        initOrg();
        initAdmin();
        registerExistingResources();
    }

    private void initOrg() {
        if (!perOrgRepository.existsById(EeConstants.ROOT_ORG_ID)) {
            PerOrg org = new PerOrg();
            org.setId(EeConstants.ROOT_ORG_ID);
            org.setPid(0L);
            org.setName(Translator.get("ee_org_default"));
            org.setRootPath(String.valueOf(EeConstants.ROOT_ORG_ID));
            org.setCreateTime(System.currentTimeMillis());
            org.setCreator(EeConstants.ADMIN_UID);
            perOrgRepository.save(org);
        }
        roleManage.createBuiltinRoles(EeConstants.ROOT_ORG_ID, EeConstants.ROOT_ADMIN_ROLE_ID, 2L, 3L);
    }

    private void initAdmin() {
        if (perUserRepository.existsById(EeConstants.ADMIN_UID)) {
            return;
        }
        long now = System.currentTimeMillis();
        PerUser admin = new PerUser();
        admin.setId(EeConstants.ADMIN_UID);
        admin.setAccount("admin");
        admin.setName(Translator.get("ee_sys_admin"));
        admin.setPwd(PasswordUtils.hash(initialPwd()));
        admin.setEnable(true);
        admin.setOrigin(EeConstants.ORIGIN_LOCAL);
        admin.setDefaultOid(EeConstants.ROOT_ORG_ID);
        admin.setLanguage("zh-CN");
        admin.setCreateTime(now);
        admin.setUpdateTime(now);
        admin.setLoginFailCount(0);
        admin.setMfaBound(false);
        admin.setMfaEnable(false);
        perUserRepository.save(admin);
        PerRole adminRole = roleManage.builtinRole(EeConstants.ROOT_ORG_ID, EeConstants.ROLE_TYPE_ADMIN);
        PerUserRole relation = new PerUserRole();
        relation.setId(EeConstants.ADMIN_UID);
        relation.setUid(EeConstants.ADMIN_UID);
        relation.setRid(adminRole.getId());
        relation.setOid(EeConstants.ROOT_ORG_ID);
        relation.setCreateTime(now);
        perUserRoleRepository.save(relation);
    }

    /**
     * 初始密码：沿用社区版 substitule.json 中的管理员密码 > dataease.default-pwd > 默认密码
     */
    @SuppressWarnings("unchecked")
    private String initialPwd() {
        if (StringUtils.isNotBlank(substitutePath)) {
            File file = new File(substitutePath);
            if (file.isFile()) {
                try {
                    Map<String, Object> data = new ObjectMapper().readValue(file, Map.class);
                    Object pwd = data.get("pwd");
                    if (pwd != null && StringUtils.isNotBlank(pwd.toString())) {
                        return pwd.toString();
                    }
                } catch (Exception e) {
                    LogUtil.warn("read substitute admin password failed: " + e.getMessage());
                }
            }
        }
        return StringUtils.isNotBlank(configuredPwd) ? configuredPwd : EeConstants.DEFAULT_PWD;
    }

    /**
     * 社区版升级：已有仪表板、数据大屏、数据集、数据源登记到默认组织
     */
    private void registerExistingResources() {
        QDataVisualizationInfo dv = QDataVisualizationInfo.dataVisualizationInfo;
        for (DataVisualizationInfo info : queryFactory.selectFrom(dv).where(dv.pid.ne(-1L)).fetch()) {
            if (Boolean.TRUE.equals(info.getDeleteFlag()) || busiResourceManage.registered(info.getId())) {
                continue;
            }
            int rtId = StringUtils.equalsAnyIgnoreCase(info.getType(), "dataV", "screen") ? EeConstants.RT_SCREEN : EeConstants.RT_PANEL;
            busiResourceManage.save(rtId, info.getId(), info.getName(), info.getPid(),
                    !StringUtils.equalsIgnoreCase(info.getNodeType(), "folder"),
                    info.getOrgId() != null && perOrgRepository.existsById(info.getOrgId()) ? info.getOrgId() : EeConstants.ROOT_ORG_ID,
                    Boolean.TRUE.equals(info.getMobileLayout()) ? 1 : 0, info.getStatus());
        }
        QCoreDatasetGroup ds = QCoreDatasetGroup.coreDatasetGroup;
        for (CoreDatasetGroup group : queryFactory.selectFrom(ds).fetch()) {
            if (busiResourceManage.registered(group.getId())) {
                continue;
            }
            busiResourceManage.save(EeConstants.RT_DATASET, group.getId(), group.getName(), group.getPid(),
                    !StringUtils.equalsIgnoreCase(group.getNodeType(), "folder"), EeConstants.ROOT_ORG_ID, 0, 1);
        }
        QCoreDatasource source = QCoreDatasource.coreDatasource;
        for (CoreDatasource datasource : queryFactory.selectFrom(source).fetch()) {
            if (datasource.getId() == null || datasource.getId() < 0 || busiResourceManage.registered(datasource.getId())) {
                continue;
            }
            busiResourceManage.save(EeConstants.RT_DATASOURCE, datasource.getId(), datasource.getName(), datasource.getPid(),
                    !StringUtils.equalsIgnoreCase(datasource.getType(), "folder"), EeConstants.ROOT_ORG_ID, 0, 1);
        }
    }
}
