package io.dataease.ee.permissions.user;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.api.permissions.login.dto.MfaLoginDTO;
import io.dataease.api.permissions.login.vo.MfaQrVO;
import io.dataease.api.permissions.role.dto.UserRequest;
import io.dataease.api.permissions.user.api.UserApi;
import io.dataease.api.permissions.user.bo.UserOrgItem;
import io.dataease.api.permissions.user.dto.*;
import io.dataease.api.permissions.user.vo.*;
import io.dataease.ee.auth.EeMfaManage;
import io.dataease.ee.auth.EeTokenManage;
import io.dataease.ee.auth.LoginServer;
import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.auth.EePermissionManage;
import io.dataease.ee.permissions.dao.entity.*;
import io.dataease.ee.permissions.dao.repo.PerUserPlatformRepository;
import io.dataease.ee.permissions.org.EeOrgManage;
import io.dataease.ee.permissions.role.EeRoleManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.KeywordRequest;
import io.dataease.permission.model.V3BaseUser;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.IDUtils;
import io.dataease.utils.IPUtils;
import io.dataease.utils.ServletUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@RestController("userServer")
@RequestMapping("/user")
public class UserServer implements UserApi {

    private final EeUserManage userManage;

    private final EeRoleManage roleManage;

    private final EeOrgManage orgManage;

    private final EePermissionManage permissionManage;

    private final EeMfaManage mfaManage;

    private final EeTokenManage tokenManage;

    private final EeUserImportManage importManage;

    private final PerUserPlatformRepository platformRepository;

    private final JPAQueryFactory queryFactory;

    public UserServer(EeUserManage userManage, EeRoleManage roleManage, EeOrgManage orgManage, EePermissionManage permissionManage,
                      EeMfaManage mfaManage, EeTokenManage tokenManage, EeUserImportManage importManage,
                      PerUserPlatformRepository platformRepository, JPAQueryFactory queryFactory) {
        this.userManage = userManage;
        this.roleManage = roleManage;
        this.orgManage = orgManage;
        this.permissionManage = permissionManage;
        this.mfaManage = mfaManage;
        this.tokenManage = tokenManage;
        this.importManage = importManage;
        this.platformRepository = platformRepository;
        this.queryFactory = queryFactory;
    }

    private Long currentOid() {
        return V3UserUtil.getOid();
    }

    private boolean sysAdmin() {
        return V3UserUtil.isSysAdmin();
    }

    @Override
    public PageResult<UserGridVO> pager(int goPage, int pageSize, UserGridRequest request) {
        return grid(goPage, pageSize, request, null);
    }

    @Override
    public PageResult<UserGridVO> memberPager(int goPage, int pageSize, UserGridRequest request) {
        Long oid = currentOid();
        if (oid == null) {
            return new PageResult<>(new ArrayList<>(), 0L, PageRequest.of(Math.max(goPage - 1, 0), Math.max(pageSize, 1)));
        }
        return grid(goPage, pageSize, request, oid);
    }

    private PageResult<UserGridVO> grid(int goPage, int pageSize, UserGridRequest request, Long scopeOid) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        var query = userManage.gridQuery(request, scopeOid);
        long total = query.clone().fetchCount();
        List<PerUser> users = query.offset((long) (page - 1) * size).limit(size).fetch();
        List<UserGridVO> vos = toGrid(users, scopeOid);
        return new PageResult<>(vos, total, PageRequest.of(page - 1, size));
    }

    private List<UserGridVO> toGrid(List<PerUser> users, Long scopeOid) {
        List<Long> uids = users.stream().map(PerUser::getId).toList();
        Map<Long, List<PerRole>> roles = roleManage.rolesOfUsers(uids);
        Set<Long> oids = users.stream().map(PerUser::getDefaultOid).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orgNames = new HashMap<>();
        for (Long oid : oids) {
            PerOrg org = orgManage.get(oid);
            if (org != null) {
                orgNames.put(oid, org.getName());
            }
        }
        EeUserVariableSupport variables = userManage.variables();
        List<UserGridVO> result = new ArrayList<>();
        for (PerUser user : users) {
            UserGridVO vo = new UserGridVO();
            vo.setId(user.getId());
            vo.setAccount(user.getAccount());
            vo.setName(user.getName());
            vo.setEmail(user.getEmail());
            vo.setPhonePrefix(user.getPhonePrefix());
            vo.setPhone(user.getPhone());
            vo.setEnable(user.getEnable());
            vo.setCreateTime(user.getCreateTime());
            vo.setOrigin(user.getOrigin());
            vo.setLocked(user.getLockUntil() != null && user.getLockUntil() > System.currentTimeMillis());
            vo.setSysVariable(variables.summary(user.getId()));
            List<UserGridRoleItem> roleItems = roles.getOrDefault(user.getId(), Collections.emptyList()).stream()
                    .filter(role -> scopeOid == null || scopeOid.equals(role.getOid()))
                    .map(role -> {
                        UserGridRoleItem item = new UserGridRoleItem();
                        item.setId(role.getId());
                        item.setName(role.getName());
                        return item;
                    }).toList();
            vo.setRoleItems(new ArrayList<>(roleItems));
            if (user.getDefaultOid() != null && orgNames.containsKey(user.getDefaultOid())) {
                UserOrgItem orgItem = new UserOrgItem();
                orgItem.setId(user.getDefaultOid());
                orgItem.setName(orgNames.get(user.getDefaultOid()));
                vo.setOrgItem(orgItem);
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public UserFormVO queryById(Long id) {
        return form(userManage.require(id));
    }

    private UserFormVO form(PerUser user) {
        UserFormVO vo = new UserFormVO();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setEmail(user.getEmail());
        vo.setPhonePrefix(user.getPhonePrefix());
        vo.setPhone(user.getPhone());
        vo.setEnable(user.getEnable());
        vo.setDefaultOid(user.getDefaultOid());
        PerOrg org = orgManage.get(user.getDefaultOid());
        vo.setOrgName(org == null ? null : org.getName());
        vo.setRoleIds(roleManage.roleIdsOfUser(user.getId(), user.getDefaultOid()).stream().map(String::valueOf).collect(Collectors.toList()));
        vo.setMfaEnable(Boolean.TRUE.equals(user.getMfaEnable()));
        vo.setOrigin(user.getOrigin());
        vo.setVariables(userManage.variables().userVariables(user.getId()));
        return vo;
    }

    @Override
    public UserFormVO personInfo() {
        PerUser user = userManage.require(V3UserUtil.getUid());
        UserFormVO vo = form(user);
        vo.setModel("xpack");
        vo.setIp(IPUtils.get());
        return vo;
    }

    @Override
    public UserGridVO personSysVariableInfo(Long id) {
        List<UserGridVO> vos = toGrid(List.of(userManage.require(id)), null);
        return vos.getFirst();
    }

    @Override
    public CurIpVO ipInfo() {
        CurIpVO vo = new CurIpVO();
        V3BaseUser user = V3UserUtil.getUser();
        if (user != null) {
            vo.setAccount(user.getAccount());
            vo.setName(user.getName());
        }
        vo.setIp(IPUtils.get());
        return vo;
    }

    @Override
    public String create(UserCreator creator) {
        if (!sysAdmin() || creator.getDefaultOid() == null) {
            // 组织管理员只能在当前组织内创建用户；未指定组织时放入当前组织
            creator.setDefaultOid(currentOid());
        }
        return String.valueOf(userManage.create(creator, EeConstants.ORIGIN_LOCAL, null));
    }

    @Override
    public void createPlatform(PlatformUserCreator creator) {
        userManage.create(creator, creator.getOrigin(), null);
    }

    @Override
    public void edit(UserEditor editor) {
        if (!sysAdmin()) {
            PerUser exist = userManage.require(editor.getId());
            editor.setDefaultOid(exist.getDefaultOid());
        }
        userManage.edit(editor);
    }

    @Override
    public void personEdit(UserEditor editor) {
        Long uid = V3UserUtil.getUid();
        int status = mfaManage.status();
        boolean forced = status == 1 || (status == 2 && sysAdmin());
        userManage.personEdit(uid, editor, forced);
    }

    @Override
    public void delete(Long id) {
        userManage.delete(id);
    }

    @Override
    @Transactional
    public void batchDel(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        for (Long id : ids) {
            if (EeConstants.ADMIN_UID.equals(id) || id.equals(V3UserUtil.getUid())) {
                continue;
            }
            if (!sysAdmin() && !orgManage.isMember(id, currentOid())) {
                continue;
            }
            userManage.delete(id);
        }
    }

    @Override
    public List<UserItemVO> optionForRole(UserRequest request) {
        PerRole role = roleManage.get(request.getRid());
        if (role == null) {
            return Collections.emptyList();
        }
        Set<Long> bound = new HashSet<>(roleManage.userIdsOfRole(role.getId()));
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q).where(q.defaultOid.eq(role.getOid()));
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        return query.orderBy(q.createTime.desc()).fetch().stream().filter(user -> !bound.contains(user.getId()))
                .map(this::item).toList();
    }

    @Override
    public PageResult<UserItemVO> selectedForRole(int goPage, int pageSize, UserRequest request) {
        int page = Math.max(goPage, 1);
        int size = Math.max(pageSize, 1);
        QPerUser q = QPerUser.perUser;
        QPerUserRole ur = QPerUserRole.perUserRole;
        var query = queryFactory.select(q).from(q).innerJoin(ur).on(ur.uid.eq(q.id)).where(ur.rid.eq(request.getRid()));
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        if (StringUtils.equalsIgnoreCase(request.getOrder(), "name asc")) {
            query.orderBy(q.name.asc());
        } else if (StringUtils.equalsIgnoreCase(request.getOrder(), "name desc")) {
            query.orderBy(q.name.desc());
        } else {
            query.orderBy(ur.createTime.desc());
        }
        long total = query.clone().fetchCount();
        List<UserItemVO> records = query.offset((long) (page - 1) * size).limit(size).fetch().stream().map(this::item).toList();
        return new PageResult<>(new ArrayList<>(records), total, PageRequest.of(page - 1, size));
    }

    private UserItemVO item(PerUser user) {
        UserItemVO vo = new UserItemVO();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setName(user.getName());
        vo.setEmail(user.getEmail());
        vo.setEnable(user.getEnable());
        return vo;
    }

    @Override
    public CurUserVO info() {
        V3BaseUser user = V3UserUtil.getUser();
        if (user == null) {
            DEException.throwException("token is empty");
        }
        CurUserVO vo = new CurUserVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setOid(user.getOid());
        String language = user.getLanguage();
        vo.setLanguage(StringUtils.isBlank(language) || StringUtils.equals(language, "zh_CN") ? "zh-CN" : language);
        return vo;
    }

    @Override
    public List<UserOptionVO> byCurOrg() {
        Long oid = currentOid();
        if (oid == null) {
            return new ArrayList<>();
        }
        QPerUser q = QPerUser.perUser;
        return queryFactory.selectFrom(q).where(q.defaultOid.eq(oid)).orderBy(q.createTime.desc()).fetch().stream().map(this::option).toList();
    }

    private UserOptionVO option(PerUser user) {
        UserOptionVO vo = new UserOptionVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setAccount(user.getAccount());
        return vo;
    }

    @Override
    public int userCount() {
        return (int) queryFactory.selectFrom(QPerUser.perUser).fetchCount();
    }

    @Override
    public void switchLanguage(LangSwitchRequest request) {
        userManage.switchLanguage(V3UserUtil.getUid(), request.getLang());
    }

    @Override
    public void excelTemplate() {
        importManage.template();
    }

    @Override
    public UserImportVO batchImport(MultipartFile file) {
        return importManage.importUsers(file, sysAdmin() && !V3UserUtil.getProxy().isProxy() ? null : currentOid(), currentOid());
    }

    @Override
    public void errorRecord(String key) {
        importManage.errorRecord(key);
    }

    @Override
    public void clearErrorRecord(String key) {
        importManage.clear(key);
    }

    @Override
    public String defaultPwd() {
        return userManage.defaultPwd();
    }

    @Override
    public void resetPwd(Long id) {
        checkManageable(id);
        userManage.resetPwd(id);
    }

    @Override
    public void unlock(Long id) {
        checkManageable(id);
        queryFactory.update(QPerUser.perUser).set(QPerUser.perUser.loginFailCount, 0).setNull(QPerUser.perUser.lockUntil)
                .where(QPerUser.perUser.id.eq(id)).execute();
    }

    private void checkManageable(Long uid) {
        if (sysAdmin()) {
            return;
        }
        if (!orgManage.isMember(uid, currentOid()) || EeConstants.ADMIN_UID.equals(uid)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    @Override
    public void enable(EnableSwitchRequest request) {
        userManage.enable(request.getId(), Boolean.TRUE.equals(request.getEnable()));
    }

    @Override
    public void modifyPwd(ModifyPwdRequest request) {
        Long uid = V3UserUtil.getUid();
        userManage.modifyPwd(uid, LoginServer.decrypt(request.getPwd()), LoginServer.decrypt(request.getNewPwd()));
        tokenManage.destroy(ServletUtils.getToken());
    }

    @Override
    public List<Long> firstEchelon(Long limit) {
        QPerUser q = QPerUser.perUser;
        return queryFactory.select(q.id).from(q).orderBy(q.createTime.asc()).limit(limit == null ? 0 : limit).fetch();
    }

    @Override
    public CurUserVO queryByAccount(String account) {
        PerUser user = userManage.findByAccount(account);
        if (user == null) {
            return null;
        }
        CurUserVO vo = new CurUserVO();
        vo.setId(user.getId());
        vo.setName(user.getName());
        vo.setOid(user.getDefaultOid());
        vo.setLanguage(user.getLanguage());
        return vo;
    }

    @Override
    public void adminBind(AdminBindRequest request) {
        bindPlatform(request.getUid(), request.getOrigin(), request.getSub());
    }

    @Override
    public void bind(UserBindRequest request) {
        bindPlatform(V3UserUtil.getUid(), request.getOrigin(), request.getSub());
    }

    @Transactional
    public void bindPlatform(Long uid, Integer origin, String sub) {
        if (uid == null || origin == null || StringUtils.isBlank(sub)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        QPerUserPlatform q = QPerUserPlatform.perUserPlatform;
        queryFactory.delete(q).where(q.origin.eq(origin), q.platformUid.eq(sub)).execute();
        queryFactory.delete(q).where(q.origin.eq(origin), q.uid.eq(uid)).execute();
        PerUserPlatform binding = new PerUserPlatform();
        binding.setId(IDUtils.snowID());
        binding.setUid(uid);
        binding.setOrigin(origin);
        binding.setPlatformUid(sub);
        binding.setCreateTime(System.currentTimeMillis());
        platformRepository.save(binding);
    }

    @Override
    @Transactional
    public void unBind(Integer origin) {
        Long uid = V3UserUtil.getUid();
        PerUser user = userManage.require(uid);
        if (Objects.equals(user.getOrigin(), origin)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        QPerUserPlatform q = QPerUserPlatform.perUserPlatform;
        queryFactory.delete(q).where(q.origin.eq(origin), q.uid.eq(uid)).execute();
    }

    @Override
    public List<Integer> bindStatus() {
        QPerUserPlatform q = QPerUserPlatform.perUserPlatform;
        return queryFactory.select(q.origin).distinct().from(q).where(q.uid.eq(V3UserUtil.getUid())).fetch();
    }

    @Override
    public List<Map<String, Object>> getRecipient(UserReciRequest request) {
        return listUserInfosByIds(getRecipientUserIds(request));
    }

    @Override
    public boolean orgAdmin() {
        return permissionManage.isOrgAdmin(V3UserUtil.getUid(), currentOid());
    }

    @Override
    public boolean defaultOrgAdmin() {
        return permissionManage.isOrgAdmin(V3UserUtil.getUid(), EeConstants.ROOT_ORG_ID);
    }

    @Override
    public List<Long> getRecipientUserIds(UserReciRequest request) {
        Set<Long> uids = new LinkedHashSet<>();
        if (request == null) {
            return new ArrayList<>();
        }
        if (CollectionUtils.isNotEmpty(request.getUidList())) {
            uids.addAll(request.getUidList());
        }
        if (CollectionUtils.isNotEmpty(request.getRidList())) {
            for (Long rid : request.getRidList()) {
                uids.addAll(roleManage.userIdsOfRole(rid));
            }
        }
        if (uids.isEmpty()) {
            return new ArrayList<>();
        }
        QPerUser q = QPerUser.perUser;
        return queryFactory.select(q.id).from(q).where(q.id.in(uids), q.enable.isTrue()).fetch();
    }

    @Override
    public List<Long> getUserIdByAccount(String account) {
        QPerUser q = QPerUser.perUser;
        return queryFactory.select(q.id).from(q).where(q.account.eq(account)).fetch();
    }

    @Override
    public List<Long> getUserIdByName(String name) {
        QPerUser q = QPerUser.perUser;
        return queryFactory.select(q.id).from(q).where(q.name.eq(name)).fetch();
    }

    @Override
    public List<Map<String, Object>> listUserInfosByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        QPerUser q = QPerUser.perUser;
        List<Map<String, Object>> result = new ArrayList<>();
        for (PerUser user : queryFactory.selectFrom(q).where(q.id.in(ids)).fetch()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", user.getId());
            map.put("account", user.getAccount());
            map.put("name", user.getName());
            map.put("email", user.getEmail());
            map.put("phone", user.getPhone());
            map.put("oid", user.getDefaultOid());
            map.put("origin", user.getOrigin());
            QPerUserPlatform p = QPerUserPlatform.perUserPlatform;
            Map<Integer, String> platforms = new HashMap<>();
            queryFactory.selectFrom(p).where(p.uid.eq(user.getId())).fetch().forEach(item -> platforms.put(item.getOrigin(), item.getPlatformUid()));
            map.put("platforms", platforms);
            result.add(map);
        }
        return result;
    }

    @Override
    public MfaQrVO mfaQr() {
        return mfaManage.bindQr(V3UserUtil.getUid());
    }

    @Override
    public Boolean mfaBound() {
        return mfaManage.bound(V3UserUtil.getUid());
    }

    @Override
    public void mfaBind(MfaLoginDTO dto) {
        mfaManage.bind(V3UserUtil.getUid(), dto.getCode());
    }

    @Override
    public String mfaUnbind(String code) {
        return mfaManage.unbind(V3UserUtil.getUid(), code);
    }

    @Override
    public void resetBind(Long id) {
        checkManageable(id);
        mfaManage.reset(id);
    }

    @Override
    public String userLang() {
        V3BaseUser user = V3UserUtil.getUser();
        return user == null || StringUtils.isBlank(user.getLanguage()) ? "zh-CN" : user.getLanguage();
    }

    @Override
    public List<UserReciVO> getFormatRecipient(List<Long> uidList, List<Long> ridList) {
        List<UserReciVO> result = new ArrayList<>();
        Set<Long> uids = new LinkedHashSet<>();
        if (uidList != null) {
            uids.addAll(uidList);
        }
        Map<Long, List<Long>> roleMap = new HashMap<>();
        if (ridList != null) {
            for (Long rid : ridList) {
                for (Long uid : roleManage.userIdsOfRole(rid)) {
                    uids.add(uid);
                    roleMap.computeIfAbsent(uid, key -> new ArrayList<>()).add(rid);
                }
            }
        }
        Map<Long, List<PerRole>> roles = roleManage.rolesOfUsers(uids);
        for (Long uid : uids) {
            UserReciVO vo = new UserReciVO(uid);
            List<PerRole> userRoles = roles.getOrDefault(uid, Collections.emptyList());
            vo.setRoleIds(userRoles.stream().map(PerRole::getId).toList());
            vo.setHasRootRole(userRoles.stream().anyMatch(role -> Boolean.TRUE.equals(role.getRoot())));
            vo.setCommonRoleIds(roleMap.getOrDefault(uid, Collections.emptyList()));
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<UserOptionVO> query() {
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q);
        if (!sysAdmin() || V3UserUtil.getProxy().isProxy()) {
            Long oid = currentOid();
            if (oid == null) {
                return new ArrayList<>();
            }
            query.where(q.defaultOid.eq(oid));
        }
        return query.orderBy(q.createTime.desc()).fetch().stream().map(this::option).toList();
    }

    @Override
    public List<UserItem> all(KeywordRequest request) {
        QPerUser q = QPerUser.perUser;
        var query = queryFactory.selectFrom(q);
        if (request != null && StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = "%" + request.getKeyword().trim().toLowerCase() + "%";
            query.where(q.name.lower().like(keyword).or(q.account.lower().like(keyword)));
        }
        if (!sysAdmin()) {
            query.where(q.defaultOid.eq(currentOid()));
        }
        return query.fetch().stream().map(user -> {
            UserItem item = new UserItem();
            item.setId(user.getId());
            item.setName(user.getName());
            item.setAccount(user.getAccount());
            return item;
        }).toList();
    }

    @Override
    public List<UserItem> subOrgUser(List<Long> oidList) {
        if (CollectionUtils.isEmpty(oidList)) {
            return new ArrayList<>();
        }
        Set<Long> oids = new HashSet<>();
        for (Long oid : oidList) {
            oids.addAll(orgManage.selfAndChildren(oid));
        }
        QPerUser q = QPerUser.perUser;
        return queryFactory.selectFrom(q).where(q.defaultOid.in(oids)).fetch().stream().map(user -> {
            UserItem item = new UserItem();
            item.setId(user.getId());
            item.setName(user.getName());
            item.setAccount(user.getAccount());
            return item;
        }).toList();
    }

    @Override
    public void proxyClear() {
        // 代理状态仅由请求头携带，服务端无缓存需要清理
    }
}
