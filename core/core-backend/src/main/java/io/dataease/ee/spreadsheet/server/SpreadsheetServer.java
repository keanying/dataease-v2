package io.dataease.ee.spreadsheet.server;

import io.dataease.api.spreadsheet.SpreadsheetApi;
import io.dataease.api.spreadsheet.dto.SpreadsheetCreator;
import io.dataease.api.spreadsheet.dto.SpreadsheetEditor;
import io.dataease.api.spreadsheet.vo.SpreadsheetTreeVO;
import io.dataease.api.spreadsheet.vo.SpreadsheetVO;
import io.dataease.ee.spreadsheet.manage.EeSpreadsheetManage;
import io.dataease.exception.DEException;
import io.dataease.i18n.Translator;
import io.dataease.model.BusiNodeRequest;
import io.dataease.model.ResourceDeleteRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 电子表格资源管理。资源权限由 SDK 接口上的 {@code @DePermit}（资源类型 SPREADSHEET）校验。
 */
@RestController("spreadsheetServer")
@RequestMapping("/spreadsheet")
public class SpreadsheetServer implements SpreadsheetApi {

    private final EeSpreadsheetManage manage;

    public SpreadsheetServer(EeSpreadsheetManage manage) {
        this.manage = manage;
    }

    @Override
    public SpreadsheetVO createFolder(SpreadsheetCreator creator) {
        return manage.createFolder(creator);
    }

    @Override
    public SpreadsheetVO create(SpreadsheetEditor editor) {
        return manage.create(editor);
    }

    @Override
    public SpreadsheetVO update(SpreadsheetEditor editor) {
        return manage.update(editor);
    }

    @Override
    public SpreadsheetVO get(Long id) {
        // SDK 未声明 @DePermit，这里显式校验查看权限
        if (!manage.canRead(id)) {
            DEException.throwException(Translator.get("ee_no_permission"));
        }
        return manage.findPublished(id, false);
    }

    @Override
    public SpreadsheetVO findById(SpreadsheetEditor request) {
        return manage.findPublished(request == null ? null : request.getId(), true);
    }

    @Override
    public SpreadsheetVO findEditById(SpreadsheetEditor request) {
        return manage.findEdit(request == null ? null : request.getId());
    }

    @Override
    public List<SpreadsheetTreeVO> tree(BusiNodeRequest request) {
        return manage.tree(request);
    }

    @Override
    public void move(SpreadsheetEditor editor) {
        manage.move(editor);
    }

    @Override
    public void rename(SpreadsheetEditor editor) {
        manage.rename(editor);
    }

    @Override
    public void delete(ResourceDeleteRequest request) {
        if (request == null) {
            return;
        }
        manage.delete(request.getId(), Boolean.TRUE.equals(request.getRootOrgNode()));
    }

    @Override
    public boolean nameCheck(SpreadsheetEditor editor) {
        return manage.nameExists(editor);
    }

    @Override
    public SpreadsheetVO updateStatus(SpreadsheetEditor editor) {
        return manage.updateStatus(editor);
    }

    @Override
    public SpreadsheetVO recoverToPublished(SpreadsheetEditor editor) {
        return manage.recoverToPublished(editor == null ? null : editor.getId());
    }
}
