package io.dataease.ee.relation;

import io.dataease.api.permissions.relation.api.RelationApi;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 血缘关系（组织管理中心 → 血缘分析，以及数据源/数据集删除前的血缘提示）。
 *
 * <p>SDK {@link RelationApi} 只声明了核心内部调用的方法，HTTP 接口按前端约定在此声明。</p>
 */
@RestController("relationServer")
@RequestMapping("/relation")
public class RelationServer implements RelationApi {

    private final EeRelationManage relationManage;

    public RelationServer(EeRelationManage relationManage) {
        this.relationManage = relationManage;
    }

    @PostMapping("/datasource/{id}")
    public Map<String, Object> datasource(@PathVariable("id") Long id) {
        return relationManage.datasource(id);
    }

    @PostMapping("/dataset/{id}")
    public Map<String, Object> dataset(@PathVariable("id") Long id) {
        return relationManage.dataset(id);
    }

    @PostMapping("/dv/{id}")
    public Map<String, Object> dv(@PathVariable("id") Long id) {
        return relationManage.dv(id);
    }

    @Override
    public Long getDsResource(Long id) {
        return relationManage.datasourceUsage(id);
    }

    @Override
    public Long getDatasetResource(Long id) {
        return relationManage.datasetUsage(id);
    }

    @Override
    public void checkAuth() throws DEException {
        if (V3UserUtil.getUid() == null) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }
}
