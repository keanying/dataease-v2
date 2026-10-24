package io.dataease.ee.spreadsheet.server;

import io.dataease.api.spreadsheet.SpreadsheetDataApi;
import io.dataease.api.spreadsheet.dto.PluginFieldValuesRequest;
import io.dataease.api.spreadsheet.dto.PluginQueryRequest;
import io.dataease.api.spreadsheet.vo.PluginQueryResponse;
import io.dataease.ee.spreadsheet.manage.EeSpreadsheetDataManage;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 电子表格插件取数。数据集权限、行列权限在 {@link EeSpreadsheetDataManage} 中按当前用户校验。
 */
@RestController("spreadsheetDataServer")
@RequestMapping("/spreadsheetData")
public class SpreadsheetDataServer implements SpreadsheetDataApi {

    private final EeSpreadsheetDataManage dataManage;

    public SpreadsheetDataServer(EeSpreadsheetDataManage dataManage) {
        this.dataManage = dataManage;
    }

    @Override
    public Long queryLimit() {
        return dataManage.queryLimit();
    }

    @Override
    public PluginQueryResponse queryData(PluginQueryRequest request) {
        return dataManage.queryData(request);
    }

    @Override
    public List<String> queryFieldValues(PluginFieldValuesRequest request) {
        return dataManage.queryFieldValues(request);
    }
}
