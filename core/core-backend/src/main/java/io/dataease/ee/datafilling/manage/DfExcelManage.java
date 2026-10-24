package io.dataease.ee.datafilling.manage;

import io.dataease.api.xpack.dataFilling.dto.DataFillFormTableDataRequest;
import io.dataease.api.xpack.dataFilling.dto.DataFillFormTableDataResponse;
import io.dataease.api.xpack.dataFilling.dto.DfExcelData;
import io.dataease.api.xpack.dataFilling.dto.RowDataDatum;
import io.dataease.exception.DEException;
import io.dataease.extensions.datafilling.dto.ExtTableField;
import io.dataease.i18n.Translator;
import io.dataease.utils.LogUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据填报 Excel 模板、导入与导出
 */
@Component
public class DfExcelManage {

    private static final Pattern STORED_FILE = Pattern.compile("^[0-9a-f\\-]{36}\\.(xlsx|xls)$");

    private static final int MAX_IMPORT_ROWS = 50000;

    private final DfDataManage dataManage;

    @Value("${dataease.path.data-filling:#{systemProperties['java.io.tmpdir']}/dataease-df-excel/}")
    private String excelPath;

    public DfExcelManage(DfDataManage dataManage) {
        this.dataManage = dataManage;
    }

    private String header(DfFormMeta.Column c) {
        if (c.rangeIndex() == 0) {
            return c.label() + "(" + Translator.get("ee_df_start") + ")";
        }
        if (c.rangeIndex() == 1) {
            return c.label() + "(" + Translator.get("ee_df_end") + ")";
        }
        return c.label();
    }

    private Workbook headerWorkbook(DfFormMeta meta) {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");
        Row row = sheet.createRow(0);
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        int i = 0;
        for (DfFormMeta.Column c : meta.columns()) {
            Cell cell = row.createCell(i);
            cell.setCellValue(header(c));
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, 20 * 256);
            i++;
        }
        return wb;
    }

    public void template(Long formId, HttpServletResponse response) {
        DfFormMeta meta = dataManage.meta(formId);
        try (Workbook wb = headerWorkbook(meta)) {
            write(wb, meta.form().getName() + ".xlsx", response);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
    }

    private void write(Workbook wb, String fileName, HttpServletResponse response) throws Exception {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment; filename*=utf-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (OutputStream out = response.getOutputStream()) {
            wb.write(out);
            out.flush();
        }
    }

    // ------------------------------------------------------------------ import

    public DfExcelData upload(Long formId, MultipartFile file) {
        DfFormMeta meta = dataManage.meta(formId);
        if (file == null || file.isEmpty()) {
            DEException.throwException(Translator.get("ee_df_excel_empty"));
        }
        String original = StringUtils.defaultIfBlank(file.getOriginalFilename(), "data.xlsx");
        String suffix = StringUtils.lowerCase(StringUtils.substringAfterLast(original, "."));
        if (!StringUtils.equalsAny(suffix, "xlsx", "xls")) {
            DEException.throwException(Translator.get("ee_df_excel_type"));
        }
        String id = UUID.randomUUID().toString();
        File dir = new File(excelPath);
        if (!dir.exists() && !dir.mkdirs()) {
            DEException.throwException("Failed to create directory");
        }
        File target = new File(dir, id + "." + suffix);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath());
        } catch (Exception e) {
            DEException.throwException(e.getMessage());
        }
        List<Map<String, Object>> rows;
        try {
            rows = parse(meta, target);
        } catch (RuntimeException e) {
            target.delete();
            throw e;
        }
        DfExcelData data = new DfExcelData();
        data.setId(id);
        data.setExcelName(original);
        data.setPath(target.getName());
        data.setSuffix(suffix);
        data.setFormFields(meta.fields());
        List<RowDataDatum> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            list.add(new RowDataDatum().setId(UUID.randomUUID().toString()).setData(row).setInsert(true));
        }
        data.setDataList(list);
        return data;
    }

    /**
     * 确认导入，返回新数据主键
     */
    public List<String> confirm(Long formId, Map<String, String> request, Long userTaskId) {
        DfFormMeta meta = dataManage.meta(formId);
        String path = request == null ? null : request.get("excelPath");
        String id = request == null ? null : request.get("excelId");
        if (StringUtils.isBlank(path) || !STORED_FILE.matcher(path).matches()
                || (StringUtils.isNotBlank(id) && !path.startsWith(id + "."))) {
            DEException.throwException(Translator.get("ee_df_excel_expired"));
        }
        File file = new File(excelPath, path);
        if (!file.exists()) {
            DEException.throwException(Translator.get("ee_df_excel_expired"));
        }
        List<Map<String, Object>> rows = parse(meta, file);
        List<String> keys = dataManage.insertRows(meta, rows, userTaskId);
        if (!file.delete()) {
            LogUtil.warn("failed to delete uploaded excel " + file.getName());
        }
        return keys;
    }

    private List<Map<String, Object>> parse(DfFormMeta meta, File file) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (InputStream in = new FileInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null || sheet.getRow(sheet.getFirstRowNum()) == null) {
                DEException.throwException(Translator.get("ee_df_excel_empty"));
            }
            int headerIndex = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(headerIndex);
            DataFormatter formatter = new DataFormatter();
            Map<Integer, DfFormMeta.Column> mapping = new HashMap<>();
            Map<String, DfFormMeta.Column> byHeader = new HashMap<>();
            for (DfFormMeta.Column c : meta.columns()) {
                byHeader.put(header(c), c);
                byHeader.putIfAbsent(c.name(), c);
            }
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                String text = cell == null ? null : StringUtils.trim(formatter.formatCellValue(cell));
                if (text != null && byHeader.containsKey(text)) {
                    mapping.put(i, byHeader.get(text));
                }
            }
            if (mapping.isEmpty()) {
                // 表头不匹配时按模板列顺序对应
                for (int i = 0; i < meta.columns().size(); i++) {
                    mapping.put(i, meta.columns().get(i));
                }
            }
            for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                boolean any = false;
                for (Map.Entry<Integer, DfFormMeta.Column> e : mapping.entrySet()) {
                    DfFormMeta.Column c = e.getValue();
                    Object v = cellValue(row.getCell(e.getKey()), c, formatter);
                    if (!DfValues.isEmpty(v)) {
                        any = true;
                    }
                    data.put(c.name(), v);
                }
                if (!any) {
                    continue;
                }
                for (DfFormMeta.Column c : meta.columns()) {
                    data.putIfAbsent(c.name(), null);
                    Object v = data.get(c.name());
                    if (c.date() && !DfValues.isEmpty(v)) {
                        Long ms = DfValues.toMillis(v);
                        if (ms == null) {
                            DEException.throwException(Translator.get("ee_df_excel_row_error", r + 1,
                                    Translator.get("ee_df_value_invalid_date", c.label(), String.valueOf(v))));
                        }
                        data.put(c.name(), ms);
                    } else if (c.multiple() && !DfValues.isEmpty(v)) {
                        data.put(c.name(), DfValues.toJsonArray(v));
                    } else if ((c.type() == ExtTableField.BaseType.number || c.type() == ExtTableField.BaseType.decimal) && !DfValues.isEmpty(v)) {
                        try {
                            data.put(c.name(), new BigDecimal(String.valueOf(v).trim()));
                        } catch (Exception ex) {
                            DEException.throwException(Translator.get("ee_df_excel_row_error", r + 1,
                                    Translator.get("ee_df_value_invalid_number", c.label(), String.valueOf(v))));
                        }
                    }
                    ExtTableField.ExtTableFieldSetting s = c.field().getSettings();
                    if (s != null && s.isRequired() && DfValues.isEmpty(data.get(c.name()))) {
                        DEException.throwException(Translator.get("ee_df_excel_row_error", r + 1,
                                Translator.get("ee_df_value_required", c.label())));
                    }
                }
                result.add(data);
                if (result.size() > MAX_IMPORT_ROWS) {
                    DEException.throwException(Translator.get("ee_df_excel_too_many", MAX_IMPORT_ROWS));
                }
            }
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            DEException.throwException(Translator.get("ee_df_excel_parse_failed") + ": " + e.getMessage());
        }
        return result;
    }

    private Object cellValue(Cell cell, DfFormMeta.Column c, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell) || c.date()) {
                    if (c.date()) {
                        Date d = cell.getDateCellValue();
                        return d == null ? null : d.getTime();
                    }
                    return formatter.formatCellValue(cell);
                }
                BigDecimal d = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
                return d.scale() <= 0 ? d.toPlainString() : d.toPlainString();
            }
            case BOOLEAN -> {
                return String.valueOf(cell.getBooleanCellValue());
            }
            case BLANK, ERROR, _NONE -> {
                return null;
            }
            default -> {
                String s = StringUtils.trimToNull(cell.getStringCellValue());
                return s;
            }
        }
    }

    // ------------------------------------------------------------------ export

    private Workbook exportWorkbook(DfFormMeta meta, DataFillFormTableDataRequest request) {
        request.setWithoutLogs(true);
        request.setPageSize(0);
        request.setCurrentPage(1);
        DataFillFormTableDataResponse data = dataManage.query(meta, request);
        Workbook wb = headerWorkbook(meta);
        Sheet sheet = wb.getSheetAt(0);
        int r = 1;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.getData();
        for (Map<String, Object> row : rows) {
            Row excelRow = sheet.createRow(r++);
            int i = 0;
            for (DfFormMeta.Column c : meta.columns()) {
                Object v = row.get(c.name());
                Cell cell = excelRow.createCell(i++);
                if (v == null) {
                    continue;
                }
                if (c.date()) {
                    cell.setCellValue(DfValues.formatDate(v, c.dateType()));
                } else if (c.multiple()) {
                    List<String> list = DfJson.read(String.valueOf(v), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
                    cell.setCellValue(list == null ? String.valueOf(v) : String.join("; ", list));
                } else if (v instanceof Number n && (c.type() == ExtTableField.BaseType.number || c.type() == ExtTableField.BaseType.decimal)) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    cell.setCellValue(String.valueOf(v));
                }
            }
        }
        return wb;
    }

    public void exportToFile(Long formId, DataFillFormTableDataRequest request, String filePath) throws Exception {
        DfFormMeta meta = dataManage.meta(formId);
        try (Workbook wb = exportWorkbook(meta, request); OutputStream out = new FileOutputStream(filePath)) {
            wb.write(out);
        }
    }

    public void exportToResponse(Long formId, DataFillFormTableDataRequest request, HttpServletResponse response) throws Exception {
        DfFormMeta meta = dataManage.meta(formId);
        try (Workbook wb = exportWorkbook(meta, request)) {
            write(wb, meta.form().getName() + ".xlsx", response);
        }
    }
}
