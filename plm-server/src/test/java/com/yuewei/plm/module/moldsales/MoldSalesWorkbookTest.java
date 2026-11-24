package com.yuewei.plm.module.moldsales;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoldSalesWorkbookTest {
    @Test
    void exportsEveryMatchedModelWithTheChosenColumnsAndPublishedTotals() throws Exception {
        var selected = Map.<String, Object>of("productKey", "history:AB", "productName", "历史款式");
        var empty = Map.<String, Object>of("productKey", "product:2", "productName", "无订单款式");
        var content = IntStream.range(0, 61).mapToObj(i -> Map.<String, Object>of(
            "brandNames", List.of("Samsung"), "modelNames", List.of(i == 0 ? "=TEST" : "型号 " + i),
            "phoneCodes", List.of("0057"), "openedDate", LocalDate.of(2021, 1, 12),
            "cells", List.of(Map.of("productKey", "history:AB", "salesQuantity", 4)),
            "totalSalesQuantity", 4)).toList();
        var report = Map.<String, Object>of("content", content, "options", Map.of("products", List.of(selected, empty)),
            "totalSalesQuantity", 244, "productTotals", List.of(Map.of("productKey", "history:AB", "salesQuantity", 244)));
        var start = LocalDate.of(2024, 1, 1);
        var end = LocalDate.of(2025, 2, 28);
        byte[] bytes = MoldSalesWorkbook.write(report, "matrix", "sales", null,
            List.of("product:2", "history:AB"), start, end, false);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("型号总览");
            assertThat(sheet.getLastRowNum()).isEqualTo(61);
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("无订单款式");
            assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("历史款式");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("0057");
            assertThat(sheet.getRow(61).getCell(4)).isNull();
            assertThat(sheet.getRow(61).getCell(5).getNumericCellValue()).isEqualTo(4);
            assertThat(book.getSheet("统计范围").getRow(2).getCell(1).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(end);
            assertThat(book.getSheet("统计范围").getRow(4).getCell(1).getNumericCellValue()).isEqualTo(244);
        }
    }

    @Test
    void keepsCumulativeProductionSeparateFromPeriodOrdersAndOnlyOutputsAuthorizedCostColumns() throws Exception {
        var group = Map.<String, Object>of("salesQuantity", 8,
            "production", Map.of("pcTotal", 51, "tpuTotal", 50, "finishedTotal", 49),
            "cost", Map.of("totalCost", 123.25, "source", "actual"));
        var report = Map.<String, Object>of("content", List.of(group), "totalSalesQuantity", 8);
        byte[] production = MoldSalesWorkbook.write(report, "detail", "production", null, null, null, null, false);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(production))) {
            var sheet = book.getSheet("累计产量记录");
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(8);
            assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(49);
        }
        for (boolean canExportCost : List.of(false, true)) {
            byte[] sales = MoldSalesWorkbook.write(report, "detail", "sales", null, null, null, null, canExportCost);
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(sales))) {
                var sheet = book.getSheet("产品明细");
                if (canExportCost) {
                    assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(123.25);
                } else {
                    assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 6);
                    assertThat(sheet.getRow(1).getCell(6)).isNull();
                }
            }
        }
    }
}
