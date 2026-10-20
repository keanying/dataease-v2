package io.dataease.ee.permissions.resource;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.dao.auto.entity.PerBusiResource;
import io.dataease.dao.auto.entity.QPerBusiResource;
import io.dataease.dao.auto.repo.PerBusiResourceRepository;
import io.dataease.ee.common.EeConstants;
import io.dataease.permission.util.V3UserUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 业务资源登记表（per_busi_resource）：记录每个资源的类型、所属组织与目录结构。
 * 资源的增删改由各资源树扩展在核心方法执行后同步。
 */
@Component
public class EeBusiResourceManage {

    private final PerBusiResourceRepository repository;

    private final JPAQueryFactory queryFactory;

    public EeBusiResourceManage(PerBusiResourceRepository repository, JPAQueryFactory queryFactory) {
        this.repository = repository;
        this.queryFactory = queryFactory;
    }

    public PerBusiResource get(Long id) {
        return id == null ? null : repository.findById(id).orElse(null);
    }

    /**
     * 登记或更新资源
     *
     * @return 是否为新登记
     */
    @Transactional
    public boolean save(int rtId, Long id, String name, Long pid, boolean leaf, Long oid, Integer extraFlag, Integer extraFlag1) {
        if (id == null) {
            return false;
        }
        PerBusiResource exist = get(id);
        long now = System.currentTimeMillis();
        Long safePid = pid == null || pid < 0 ? 0L : pid;
        if (exist == null) {
            PerBusiResource resource = new PerBusiResource();
            resource.setId(id);
            resource.setRtId(rtId);
            resource.setName(StringUtils.defaultIfBlank(name, String.valueOf(id)));
            resource.setPid(safePid);
            resource.setLeaf(leaf);
            resource.setOrgId(oid);
            resource.setExtraFlag(ObjectUtils.defaultIfNull(extraFlag, 0));
            resource.setExtraFlag1(ObjectUtils.defaultIfNull(extraFlag1, 1));
            resource.setOrgRoot(false);
            resource.setCreateTime(now);
            Long uid = V3UserUtil.getUid();
            resource.setCreator(uid == null ? EeConstants.ADMIN_UID : uid);
            resource.setRootPath(buildRootPath(safePid, id));
            repository.save(resource);
            return true;
        }
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        var update = queryFactory.update(q).where(q.id.eq(id));
        boolean changed = false;
        if (StringUtils.isNotBlank(name) && !StringUtils.equals(name, exist.getName())) {
            update.set(q.name, name);
            changed = true;
        }
        if (pid != null && !Objects.equals(safePid, exist.getPid())) {
            update.set(q.pid, safePid);
            update.set(q.rootPath, buildRootPath(safePid, id));
            changed = true;
        }
        if (extraFlag != null) {
            update.set(q.extraFlag, extraFlag);
            changed = true;
        }
        if (extraFlag1 != null) {
            update.set(q.extraFlag1, extraFlag1);
            changed = true;
        }
        if (changed) {
            update.execute();
            if (pid != null && !Objects.equals(safePid, exist.getPid())) {
                refreshChildrenPath(id);
            }
        }
        return false;
    }

    @Transactional
    public void rename(Long id, String name) {
        if (id == null || StringUtils.isBlank(name)) {
            return;
        }
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        queryFactory.update(q).set(q.name, name).where(q.id.eq(id)).execute();
    }

    @Transactional
    public void move(Long id, Long pid) {
        PerBusiResource exist = get(id);
        if (exist == null) {
            return;
        }
        save(exist.getRtId(), id, null, pid, Boolean.TRUE.equals(exist.getLeaf()), exist.getOrgId(), null, null);
    }

    /**
     * 删除资源及其全部下级，返回被删除的资源ID
     */
    @Transactional
    public List<Long> deleteWithChildren(Long id) {
        if (id == null) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(childrenIds(id));
        ids.add(id);
        repository.deleteAllByIdIn(ids);
        return ids;
    }

    /**
     * 全部下级资源ID（不含自身）
     */
    public List<Long> childrenIds(Long id) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        List<Long> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(id);
        Set<Long> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            List<Long> kids = queryFactory.select(q.id).from(q).where(q.pid.eq(current)).fetch();
            for (Long kid : kids) {
                if (!visited.contains(kid)) {
                    result.add(kid);
                    stack.push(kid);
                }
            }
        }
        return result;
    }

    /**
     * 资源的祖先目录ID（不含自身、不含根 0），由近到远
     */
    public List<Long> ancestorIds(Long id) {
        List<Long> result = new ArrayList<>();
        PerBusiResource current = get(id);
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getPid() != null && current.getPid() > 0 && visited.add(current.getId())) {
            result.add(current.getPid());
            current = get(current.getPid());
        }
        return result;
    }

    public Long orgOf(Long id) {
        PerBusiResource resource = get(id);
        return resource == null ? null : resource.getOrgId();
    }

    /**
     * 指定组织、类型的全部资源
     */
    public List<PerBusiResource> listByOrg(int rtId, Long oid) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        return queryFactory.selectFrom(q).where(q.rtId.eq(rtId), q.orgId.eq(oid)).fetch();
    }

    public Set<Long> idsByOrg(int rtId, Long oid) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        return new HashSet<>(queryFactory.select(q.id).from(q).where(q.rtId.eq(rtId), q.orgId.eq(oid)).fetch());
    }

    public Map<Long, PerBusiResource> mapByIds(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyMap();
        }
        Map<Long, PerBusiResource> result = new HashMap<>();
        repository.findAllById(ids).forEach(item -> result.put(item.getId(), item));
        return result;
    }

    public boolean registered(Long id) {
        return id != null && repository.existsById(id);
    }

    @Transactional
    public void deleteByOrg(Long oid) {
        repository.deleteByOrgId(oid);
    }

    @Transactional
    public void changeOrg(Collection<Long> ids, Long targetOid) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        queryFactory.update(q).set(q.orgId, targetOid).where(q.id.in(ids)).execute();
    }

    private String buildRootPath(Long pid, Long id) {
        if (pid == null || pid == 0L) {
            return String.valueOf(id);
        }
        PerBusiResource parent = get(pid);
        String parentPath = parent == null || StringUtils.isBlank(parent.getRootPath()) ? String.valueOf(pid) : parent.getRootPath();
        String path = parentPath + "," + id;
        return path.length() > 255 ? StringUtils.right(path, 255) : path;
    }

    private void refreshChildrenPath(Long id) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(id);
        Set<Long> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (Long kid : queryFactory.select(q.id).from(q).where(q.pid.eq(current)).fetch()) {
                queryFactory.update(q).set(q.rootPath, buildRootPath(current, kid)).where(q.id.eq(kid)).execute();
                stack.push(kid);
            }
        }
    }
}
