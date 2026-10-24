package io.dataease.ee.log;

import io.dataease.api.log.LogApi;
import io.dataease.api.log.dto.LogGridRequest;
import io.dataease.api.log.vo.LogGridVO;
import io.dataease.api.log.vo.LogOpVO;
import io.dataease.api.permissions.org.api.OrgApi;
import io.dataease.ee.permissions.menu.EeMenuManage;
import io.dataease.exception.DEException;
import io.dataease.api.export.BaseExportApi;
import io.dataease.i18n.Translator;
import io.dataease.permission.util.V3UserUtil;
import io.dataease.result.PageResult;
import io.dataease.utils.JsonUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;

/**
 * 操作日志（工具箱 → 操作日志，菜单 32）
 */
@RestController("logServer")
@RequestMapping("/log")
public class LogServer implements LogApi {

    /**
     * 工具箱 → 操作日志 菜单ID
     */
    public static final long LOG_MENU_ID = 32L;

    private final EeLogManage logManage;

    private final EeMenuManage menuManage;

    private final ObjectProvider<OrgApi> orgApi;

    private final ObjectProvider<BaseExportApi> exportApi;

    public LogServer(EeLogManage logManage, EeMenuManage menuManage, ObjectProvider<OrgApi> orgApi,
                     ObjectProvider<BaseExportApi> exportApi) {
        this.logManage = logManage;
        this.menuManage = menuManage;
        this.orgApi = orgApi;
        this.exportApi = exportApi;
    }

    private void checkMenu() {
        if (V3UserUtil.getUid() == null || !menuManage.canAccess(LOG_MENU_ID)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
    }

    private List<Long> orgScope() {
        OrgApi api = orgApi.getIfAvailable();
        return api == null ? null : api.logScope();
    }

    @Override
    public PageResult<LogGridVO> pager(int goPage, int pageSize, LogGridRequest request) {
        checkMenu();
        return logManage.pager(goPage, pageSize, request == null ? new LogGridRequest() : request, logManage.scope(orgScope()));
    }

    @Override
    public void export(LogGridRequest request) {
        checkMenu();
        LogGridRequest body = request == null ? new LogGridRequest() : request;
        // 范围在 HTTP 线程上解析，序列化后交给导出中心
        List<Long> scope = orgScope();
        HashMap<String, Object> params = new HashMap<>();
        params.put("name", Translator.get("ee_log_export_name"));
        params.put("logGridRequest", JsonUtil.toJSONString(body).toString());
        params.put("logScope", JsonUtil.toJSONString(scope).toString());
        Long proxyOid = V3UserUtil.getProxy().getProxyOid();
        params.put("proxy", proxyOid);
        params.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        BaseExportApi api = exportApi.getObject();
        api.addTask("0", "log", params, V3UserUtil.getUid(), V3UserUtil.getOid());
    }

    @Override
    public List<LogOpVO> logOptions() {
        checkMenu();
        return logManage.options();
    }

    /**
     * 导出中心回调（异步线程，身份/代理/语言已由导出中心设置）
     */
    @Override
    public void writeExcel(String filePath, LogGridRequest request, List<Long> scope) {
        try {
            logManage.writeExcel(filePath, request == null ? new LogGridRequest() : request, logManage.scope(scope));
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(e);
        }
    }
}
