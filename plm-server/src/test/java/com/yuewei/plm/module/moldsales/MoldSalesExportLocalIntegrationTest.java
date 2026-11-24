package com.yuewei.plm.module.moldsales;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIf("com.yuewei.plm.module.moldsales.MoldSalesLocalFixture#databaseConfigured")
class MoldSalesExportLocalIntegrationTest extends MoldSalesLocalFixture {
    @Test
    void exportMatchesTheReportPeriodAndHidesFinancialColumnsWithoutExportPermission() throws Exception {
        when(access.hasPermission("finance:export")).thenReturn(false);
        jdbc.update("""
            insert into plm_mold_sales_line
            select 'qa-next-day',snapshot_id,'qa-next','QA2','closed','NFA4030005701','FA','0057',200,'eligible',
              '{"businessDate":"2025-01-02","name":"Phone A"}'::jsonb from plm_mold_sales_snapshot
            """);
        var mvc = MockMvcBuilders.standaloneSetup(new MoldSalesController(service)).build();
        var date = LocalDate.of(2025, 1, 1);
        var page = service.page(1, 1, "", "withSales", null, null, "sales", null, null,
            List.of("product:1"), "quantityDesc", date, date, false);
        var response = mvc.perform(get("/api/v1/mold-sales/export")
                .param("view", "sales").param("presentation", "detail").param("productKeys", "product:1")
                .param("filter", "withSales").param("sort", "quantityDesc")
                .param("dateFrom", date.toString()).param("dateTo", date.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var data = workbook.getSheet("产品明细");
            assertThat(data.getLastRowNum()).isEqualTo(1);
            assertThat(data.getRow(0).getLastCellNum()).isEqualTo((short) 6);
            assertThat(data.getRow(1).getCell(3).getStringCellValue()).isEqualTo("0057");
            assertThat(data.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(100);
            assertThat(workbook.getSheet("统计范围").getRow(4).getCell(1).getNumericCellValue())
                .isEqualTo(((Number) page.get("totalSalesQuantity")).doubleValue());
        }
    }
}
