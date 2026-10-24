package io.dataease.ee.log;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.dataease.constant.LogST;
import io.dataease.dao.auto.entity.*;
import io.dataease.ee.permissions.dao.entity.QPerOrg;
import io.dataease.ee.permissions.dao.entity.QPerRole;
import io.dataease.ee.permissions.dao.entity.QPerUser;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 解析操作日志中资源的名称与路径（“目录/子目录/资源”）。
 *
 * <p>先询问 {@link EeLogResourceResolver} 扩展，再按资源类型查询核心表，最后回退到 per_busi_resource。</p>
 */
@Component
public class EeLogResourceManage {

    private static final int MAX_DEPTH = 32;

    private final JPAQueryFactory queryFactory;

    private final ObjectProvider<EeLogResourceResolver> resolvers;

    public EeLogResourceManage(JPAQueryFactory queryFactory, ObjectProvider<EeLogResourceResolver> resolvers) {
        this.queryFactory = queryFactory;
        this.resolvers = resolvers;
    }

    /**
     * 解析资源展示文本，失败返回 null（不抛异常）
     */
    public String resolve(LogST st, Object resourceId) {
        if (st == null || resourceId == null || StringUtils.isBlank(resourceId.toString())) {
            return null;
        }
        try {
            for (EeLogResourceResolver resolver : resolvers.orderedStream().toList()) {
                if (resolver.supports(st.getValue())) {
                    String text = resolver.resolve(st.getValue(), resourceId);
                    if (StringUtils.isNotBlank(text)) {
                        return text;
                    }
                }
            }
            return builtin(st, resourceId);
        } catch (Exception e) {
            LogUtil.warn("resolve log resource failed: " + st + " " + resourceId + " " + e.getMessage());
            return null;
        }
    }

    private String builtin(LogST st, Object resourceId) {
        if (st == LogST.DATA) {
            QCoreExportTask q = QCoreExportTask.coreExportTask;
            return queryFactory.select(q.fileName).from(q).where(q.id.eq(resourceId.toString())).fetchFirst();
        }
        Long id = toLong(resourceId);
        if (id == null) {
            return null;
        }
        String text = switch (st) {
            case PANEL, SCREEN -> {
                String path = visualizationPath(id);
                // 导出图表时记录的是图表ID
                yield path != null ? path : chartPath(id);
            }
            case VIEW -> chartPath(id);
            case DATASET -> path(id, this::datasetNode);
            case DATASOURCE -> path(id, this::datasourceNode);
            case USER -> {
                QPerUser q = QPerUser.perUser;
                Tuple t = queryFactory.select(q.name, q.account).from(q).where(q.id.eq(id)).fetchFirst();
                yield t == null ? null : t.get(q.name) + "(" + t.get(q.account) + ")";
            }
            case ROLE -> {
                QPerRole q = QPerRole.perRole;
                yield queryFactory.select(q.name).from(q).where(q.id.eq(id)).fetchFirst();
            }
            case ORG -> {
                QPerOrg q = QPerOrg.perOrg;
                yield queryFactory.select(q.name).from(q).where(q.id.eq(id)).fetchFirst();
            }
            default -> null;
        };
        return text != null ? text : path(id, this::busiNode);
    }

    private String visualizationPath(Long id) {
        return path(id, this::visualizationNode);
    }

    private String chartPath(Long id) {
        QCoreChartView q = QCoreChartView.coreChartView;
        Tuple t = queryFactory.select(q.title, q.sceneId).from(q).where(q.id.eq(id)).fetchFirst();
        if (t == null) {
            return null;
        }
        String title = t.get(q.title);
        Long sceneId = t.get(q.sceneId);
        String dv = sceneId == null ? null : visualizationPath(sceneId);
        if (dv == null) {
            return title;
        }
        return StringUtils.isBlank(title) ? dv : dv + "/" + title;
    }

    private Node visualizationNode(Long id) {
        QDataVisualizationInfo q = QDataVisualizationInfo.dataVisualizationInfo;
        Tuple t = queryFactory.select(q.name, q.pid).from(q).where(q.id.eq(id)).fetchFirst();
        return t == null ? null : new Node(t.get(q.name), t.get(q.pid));
    }

    private Node datasetNode(Long id) {
        QCoreDatasetGroup q = QCoreDatasetGroup.coreDatasetGroup;
        Tuple t = queryFactory.select(q.name, q.pid).from(q).where(q.id.eq(id)).fetchFirst();
        return t == null ? null : new Node(t.get(q.name), t.get(q.pid));
    }

    private Node datasourceNode(Long id) {
        QCoreDatasource q = QCoreDatasource.coreDatasource;
        Tuple t = queryFactory.select(q.name, q.pid).from(q).where(q.id.eq(id)).fetchFirst();
        return t == null ? null : new Node(t.get(q.name), t.get(q.pid));
    }

    private Node busiNode(Long id) {
        QPerBusiResource q = QPerBusiResource.perBusiResource;
        Tuple t = queryFactory.select(q.name, q.pid).from(q).where(q.id.eq(id)).fetchFirst();
        return t == null ? null : new Node(t.get(q.name), t.get(q.pid));
    }

    /**
     * 自底向上拼接路径；某一级在业务表中不存在时（如组织虚拟根目录）尝试 per_busi_resource
     */
    private String path(Long id, Function<Long, Node> loader) {
        Node self = loader.apply(id);
        if (self == null) {
            return null;
        }
        LinkedList<String> names = new LinkedList<>();
        names.addFirst(self.name());
        Set<Long> visited = new HashSet<>();
        visited.add(id);
        Long pid = self.pid();
        int depth = 0;
        while (pid != null && pid > 0 && visited.add(pid) && depth++ < MAX_DEPTH) {
            Node parent = loader.apply(pid);
            if (parent == null) {
                parent = busiNode(pid);
            }
            if (parent == null) {
                break;
            }
            names.addFirst(parent.name());
            pid = parent.pid();
        }
        return String.join("/", names.stream().filter(Objects::nonNull).toList());
    }

    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (StringUtils.isNumeric(text) && text.length() < 20) {
            return Long.parseLong(text);
        }
        return null;
    }

    private record Node(String name, Long pid) {
    }
}
