package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MoldScheduleSheetParserTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static ObjectNode sheet(String... dates){var source=JSON.createObjectNode();var values=source.putArray("displayValues");values.addArray();var header=values.addArray();for(String s:List.of("序号 Número","款式 Estilo","品牌 Marca","型号 Modelo","开模日期 Fecha","计划模具交付日期 Fecha","计划到达工厂日期 Fecha","实际发模日期 Fecha","抵达墨西哥 Llegada","观察 Observaciones","备注"))header.add(s);
        var row=values.addArray();row.add("2").add("Waveshell 薇武士").add("iPhone").add("IP17PRO/18PRO");for(String d:dates)row.add(d);while(row.size()<9)row.add("");row.add("En producción MEX · OK 3K").add("来源备注");return source;}
    @Test void distinguishes_plans_formulas_facts_and_does_not_infer_production_or_quantities(){var s=sheet("2026/10/1","2026/10/15","2026/10/22","16/10/2026","20/10/2026");var formulas=s.putArray("formulas");formulas.addArray();formulas.addArray();formulas.addArray().add("").add("").add("").add("").add("").add("").add("=F3+7");var row=MoldScheduleSheetParser.parse(s).get(0);assertThat(row.dates()).containsEntry("openedDate","2026-10-01").containsEntry("forecastFactoryDate","2026-10-22").containsEntry("actualMexicoArrivalDate","2026-10-20").doesNotContainKeys("plannedFactoryArrivalDate","actualProductionDate","pcStatus","finishedTotal");assertThat(row.arrivalFormula()).isTrue();assertThat(row.notes()).contains("OK 3K","来源备注");assertThat(row.issues()).isEmpty();}
    @Test void missing_data_does_not_become_clearing_or_zero(){var row=MoldScheduleSheetParser.parse(sheet("2026-10-01","","暂无数据","—","暂无数据")).get(0);assertThat(row.dates()).containsOnlyKeys("openedDate");assertThat(row.issues()).isEmpty();}
    @Test void invalid_calendar_date_is_a_row_issue_not_a_guess(){var row=MoldScheduleSheetParser.parse(sheet("2026-02-30")).get(0);assertThat(row.dates()).isEmpty();assertThat(row.issues()).anyMatch(s->s.contains("开模日期"));}
    @Test void changed_headers_and_empty_sources_stop_the_update(){assertThatThrownBy(()->MoldScheduleSheetParser.parse(JSON.createObjectNode())).hasMessageContaining("在线表");var s=sheet();((ArrayNode)s.path("displayValues")).remove(2);assertThatThrownBy(()->MoldScheduleSheetParser.parse(s)).hasMessageContaining("有效开模记录");}
    @Test void duplicates_mark_both_rows_and_row_insertions_do_not_change_identity(){var s=sheet("2026-10-01");var rows=(ArrayNode)s.path("displayValues");rows.add(rows.path(2).deepCopy());var parsed=MoldScheduleSheetParser.parse(s);assertThat(parsed).hasSize(2).allMatch(r->!r.issues().isEmpty());assertThat(parsed.get(0).key()).isEqualTo(parsed.get(1).key());}
    @Test void explicit_excel_serials_and_year_first_and_spanish_dates_are_supported(){assertThat(MoldScheduleSheetParser.date("2026-10-1")).hasToString("2026-10-01");assertThat(MoldScheduleSheetParser.date("23/07/2026")).hasToString("2026-07-23");assertThat(MoldScheduleSheetParser.date("46296")).hasToString("2026-10-01");}
    @Test void a_fixed_range_with_blank_trailing_rows_is_not_mistaken_for_truncation(){var s=sheet("2026-10-01");var rows=(ArrayNode)s.path("displayValues");while(rows.size()<1000)rows.addArray();assertThat(MoldScheduleSheetParser.parse(s)).hasSize(1);rows.path(999).deepCopy();((ArrayNode)rows.path(999)).add("new data");assertThatThrownBy(()->MoldScheduleSheetParser.parse(s)).hasMessageContaining("上限");}
    @Test void changed_formula_is_reviewed_instead_of_reusing_an_incorrect_seven_day_dependency(){var s=sheet("2026-10-01","2026-10-15","2026-10-29");var formulas=s.putArray("formulas");formulas.addArray();formulas.addArray();formulas.addArray().add("").add("").add("").add("").add("").add("").add("=F3+14");var row=MoldScheduleSheetParser.parse(s).get(0);assertThat(row.arrivalFormula()).isFalse();assertThat(row.issues()).anyMatch(v->v.contains("公式已改变"));}
}
