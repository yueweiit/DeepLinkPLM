package com.yuewei.plm.module.moldsales;

import com.yuewei.plm.common.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Writes the already-authorized report projection without recalculating or re-paging it. */
final class MoldSalesWorkbook {
    private record Column(String title, int width, Function<Map<String, Object>, Object> value) {}

    private MoldSalesWorkbook() {}

    static byte[] write(Map<String, Object> report, String presentation, String view,
                        List<Long> selectedIds, List<String> selectedKeys,
                        LocalDate dateFrom, LocalDate dateTo, boolean exportCost) {
        boolean matrix = "matrix".equals(presentation);
        boolean production = "production".equals(view);
        String title = matrix ? "型号总览" : production ? "累计产量记录" : "产品明细";
        var content = rows(report.get("content"));
        var products = matrix ? selectedProducts(report, selectedIds, selectedKeys) : List.<Map<String, Object>>of();
        var columns = matrix ? matrixColumns(products) : detailColumns(production, exportCost);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(title);
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));

            var header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i).title());
                header.getCell(i).setCellStyle(headerStyle);
                sheet.setColumnWidth(i, columns.get(i).width() * 256);
            }
            int index = 1;
            for (var source : content) {
                var values = matrix ? matrixValues(source, source.get("cells")) : source;
                var row = sheet.createRow(index++);
                for (int i = 0; i < columns.size(); i++) {
                    cell(row, i, columns.get(i).value().apply(values), dateStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            if (!content.isEmpty()) sheet.setAutoFilter(new CellRangeAddress(0, content.size(), 0, columns.size() - 1));

            var summary = workbook.createSheet("统计范围");
            summary.setColumnWidth(0, 28 * 256);
            summary.setColumnWidth(1, 64 * 256);
            pair(summary, 0, "报表", title, dateStyle);
            pair(summary, 1, "订单日期起", dateFrom == null ? "不限" : dateFrom, dateStyle);
            pair(summary, 2, "订单日期止", dateTo == null ? "不限" : dateTo, dateStyle);
            pair(summary, 3, "当前筛选记录数", content.size(), dateStyle);
            pair(summary, 4, "当前筛选有效订单合计（件）", report.get("totalSalesQuantity"), dateStyle);
            int summaryRow = 5;
            if (matrix) {
                var totals = matrixValues(Map.of(), report.get("productTotals"));
                for (var product : products) {
                    pair(summary, summaryRow++, string(product.get("productName")),
                        totals.get(MoldSalesHistoricalProjection.productKey(product)), dateStyle);
                }
            }
            if (production) {
                pair(summary, summaryRow, "累计产量口径", "PC、TPU、成品按全期累计记录统计。日期范围用于有效订单。", dateStyle);
            } else if (exportCost) {
                pair(summary, summaryRow, "成本口径", "每件分摊按全期有效销量计算。", dateStyle);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new BusinessException(50001, "Excel 生成失败，请重试");
        }
    }

    private static List<Map<String, Object>> selectedProducts(Map<String, Object> report, List<Long> ids, List<String> keys) {
        var products = rows(value(report.get("options"), "products"));
        if (keys != null) {
            var byKey = new LinkedHashMap<String, Map<String, Object>>();
            for (var product : products) byKey.putIfAbsent(MoldSalesHistoricalProjection.productKey(product), product);
            return keys.stream().distinct().map(byKey::get).filter(Objects::nonNull).toList();
        }
        return products.stream().filter(p -> ids == null || ids.contains(p.get("productLineId"))).toList();
    }

    private static List<Column> matrixColumns(List<Map<String, Object>> products) {
        var columns = new ArrayList<>(List.of(
            new Column("品牌", 18, r -> join(r.get("brandNames"))),
            new Column("手机型号", 55, r -> join(r.get("modelNames"))),
            new Column("手机编码", 19, r -> join(r.get("phoneCodes"))),
            new Column("开模日期", 17, r -> MoldSalesRules.date(r.get("openedDate")))));
        for (var product : products) {
            String key = MoldSalesHistoricalProjection.productKey(product);
            columns.add(new Column(string(product.get("productName")), 23, r -> r.get(key)));
        }
        columns.add(new Column("已选产品合计（件）", 23, r -> r.get("totalSalesQuantity")));
        return columns;
    }

    private static Map<String, Object> matrixValues(Map<String, Object> row, Object cells) {
        var result = new HashMap<>(row);
        for (var item : rows(cells)) result.put(MoldSalesHistoricalProjection.productKey(item), item.get("salesQuantity"));
        return result;
    }

    private static List<Column> detailColumns(boolean production, boolean exportCost) {
        var columns = new ArrayList<>(List.of(
            new Column("品牌", 18, r -> join(r.get("brandNames"))),
            new Column("产品", 24, r -> Objects.toString(r.get("productName"), string(r.get("styleName")))),
            new Column("手机型号", 55, r -> join(r.get("modelNames"))),
            new Column("手机编码", 19, r -> join(r.get("phoneCodes"))),
            new Column("开模日期", 17, r -> MoldSalesRules.date(r.get("openedDate"))),
            new Column("有效订单（件）", 18, r -> r.get("salesQuantity"))));
        if (production) {
            columns.add(new Column("PC 累计（件）", 18, r -> value(r.get("production"), "pcTotal")));
            columns.add(new Column("TPU 累计（件）", 18, r -> value(r.get("production"), "tpuTotal")));
            columns.add(new Column("成品累计（件）", 18, r -> value(r.get("production"), "finishedTotal")));
        } else if (exportCost) {
            columns.add(new Column("模具成本（CNY）", 20, r -> cost(r, "totalCost")));
            columns.add(new Column("每件分摊（CNY）", 20, r -> cost(r, "perUnitCost")));
            columns.add(new Column("参考回本销量（件）", 23, r -> cost(r, "breakEvenUnits")));
            columns.add(new Column("成本依据", 18, r -> {
                if (Boolean.TRUE.equals(r.get("costConflict"))) return "成本归属待核对";
                if (r.get("cost") == null) return null;
                if ("actual".equals(cost(r, "source"))) return "实际成本";
                return Boolean.TRUE.equals(r.get("sourceOnly")) ? "单套参考" : "预算参考";
            }));
        }
        return columns;
    }

    private static Object cost(Map<String, Object> row, String key) {
        return Boolean.TRUE.equals(row.get("costConflict")) ? null : value(row.get("cost"), key);
    }

    private static Object value(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static String string(Object value) {
        return Objects.toString(value, "");
    }

    private static String join(Object value) {
        return value instanceof Collection<?> parts
            ? parts.stream().map(MoldSalesWorkbook::string).collect(Collectors.joining(" / ")) : string(value);
    }

    private static void pair(Sheet sheet, int index, String label, Object value, CellStyle dateStyle) {
        var row = sheet.createRow(index);
        row.createCell(0).setCellValue(label);
        cell(row, 1, value, dateStyle);
    }

    private static void cell(Row row, int column, Object value, CellStyle dateStyle) {
        if (value == null) return;
        var cell = row.createCell(column);
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        } else cell.setCellValue(value.toString());
    }
}
