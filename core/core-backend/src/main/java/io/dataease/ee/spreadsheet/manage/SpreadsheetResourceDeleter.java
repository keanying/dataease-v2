package io.dataease.ee.spreadsheet.manage;

import io.dataease.ee.common.EeConstants;
import io.dataease.ee.permissions.resource.EeResourceDeleter;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 删除组织时级联删除其电子表格
 */
@Component
public class SpreadsheetResourceDeleter implements EeResourceDeleter {

    private final EeSpreadsheetManage manage;

    public SpreadsheetResourceDeleter(@Lazy EeSpreadsheetManage manage) {
        this.manage = manage;
    }

    @Override
    public int rtId() {
        return EeConstants.RT_SPREADSHEET;
    }

    @Override
    public void delete(Long id) {
        manage.deleteInternal(id);
    }
}
