package io.dataease.ee.report.render;

import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.ChartViewFieldBaseDTO;
import io.dataease.utils.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * 报告附件生成：图表数据 Excel、图片转 PDF
 */
public final class ReportFileUtils {

    private ReportFileUtils() {
    }

    /**
     * 一个图表的数据
     *
     * @param title  图表名称（作为 sheet 名）
     * @param fields 图表结果字段（chart data 中的 fields）
     * @param rows   图表结果行（chart data 中的 tableRow，key 为字段 dataeaseName）
     */
    public record SheetData(String title, List<?> fields, List<Map<String, Object>> rows) {
    }

    public static boolean isPdf(byte[] data) {
        return data != null && data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    /**
     * 把图片包装为单页 PDF（截图服务未直接返回 PDF 时使用）
     */
    public static byte[] imageToPdf(byte[] image) throws Exception {
        Image img = Image.getInstance(image);
        Rectangle size = new Rectangle(img.getWidth(), img.getHeight());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(size, 0, 0, 0, 0);
        PdfWriter.getInstance(document, out);
        document.open();
        img.setAbsolutePosition(0, 0);
        document.add(img);
        document.close();
        return out.toByteArray();
    }

    public static byte[] excel(List<SheetData> sheets) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headStyle.setFont(font);
            headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Set<String> names = new HashSet<>();
            for (SheetData data : sheets) {
                Sheet sheet = wb.createSheet(sheetName(data.title(), names));
                List<String[]> columns = columns(data.fields());
                Row head = sheet.createRow(0);
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = head.createCell(c);
                    cell.setCellValue(columns.get(c)[1]);
                    cell.setCellStyle(headStyle);
                    sheet.setColumnWidth(c, 255 * 20);
                }
                int r = 1;
                for (Map<String, Object> row : data.rows()) {
                    Row line = sheet.createRow(r++);
                    for (int c = 0; c < columns.size(); c++) {
                        Object value = row.get(columns.get(c)[0]);
                        Cell cell = line.createCell(c);
                        if (value == null) {
                            continue;
                        }
                        if (value instanceof Number number) {
                            cell.setCellValue(number instanceof BigDecimal bd ? bd.doubleValue() : number.doubleValue());
                        } else {
                            cell.setCellValue(value.toString());
                        }
                    }
                }
            }
            if (sheets.isEmpty()) {
                wb.createSheet("Sheet1");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * [dataeaseName, 显示名]
     */
    private static List<String[]> columns(List<?> fields) {
        List<String[]> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (fields == null) {
            return columns;
        }
        for (Object field : fields) {
            String key;
            String name;
            if (field instanceof ChartViewFieldBaseDTO dto) {
                key = dto.getDataeaseName();
                name = StringUtils.defaultIfBlank(dto.getChartShowName(), dto.getName());
            } else if (field instanceof DatasetTableFieldDTO dto) {
                key = dto.getDataeaseName();
                name = dto.getName();
            } else {
                Map<?, ?> map = field instanceof Map<?, ?> m ? m : JsonUtil.parseObject(JsonUtil.toJSONString(field).toString(), Map.class);
                key = map.get("dataeaseName") == null ? null : map.get("dataeaseName").toString();
                Object showName = map.get("chartShowName");
                name = showName != null && StringUtils.isNotBlank(showName.toString()) ? showName.toString()
                        : String.valueOf(map.get("name"));
            }
            if (StringUtils.isBlank(key) || !seen.add(key)) {
                continue;
            }
            columns.add(new String[]{key, StringUtils.defaultIfBlank(name, key)});
        }
        return columns;
    }

    private static String sheetName(String title, Set<String> used) {
        String base = StringUtils.defaultIfBlank(title, "Sheet").replaceAll("[\\\\/?*\\[\\]:]", "_");
        base = StringUtils.left(base, 28);
        String name = base;
        int i = 1;
        while (!used.add(name.toLowerCase(Locale.ROOT))) {
            name = base + "_" + (i++);
        }
        return name;
    }
}
