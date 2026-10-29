package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Reads separately captured source/DB projections; has no database connection or writer. */
@EnabledIfEnvironmentVariable(named="PLM_SHEET_AUDIT_DIR",matches=".+")
class MoldScheduleSheetReadOnlyAuditTest {
    @Test void current_source_has_42_unique_existing_physical_sets_including_separate_copy()throws Exception{
        var json=new ObjectMapper();var root=Path.of(System.getenv("PLM_SHEET_AUDIT_DIR"));
        var live=root.resolve("live-range.private.json");
        var rows=MoldScheduleSheetParser.parse(json.readTree((Files.exists(live)?live:root.resolve("actual-source-fixture.private.json")).toFile()));
        var projection=json.readTree(root.resolve("actual-match-fixture.private.json").toFile());
        List<Map<String,Object>> sets=json.convertValue(projection.path("sets"),new TypeReference<>(){}),brands=json.convertValue(projection.path("brands"),new TypeReference<>(){});
        Map<String,String> aliases=Map.of("Waveshell 薇武士","WV","Súper Capitán超队","HA","Rainbow 2.0亮甲","BA","Fantasía幻影","FA","Blindaje圣殿","BD");
        var out=new ArrayList<Map<String,Object>>();Set<Long> unique=new HashSet<>();
        for(var row:rows){var match=MoldScheduleSheetMatcher.match(row,sets,brands,aliases);var result=new LinkedHashMap<String,Object>();result.put("rowNo",row.rowNo());result.put("style",row.style());result.put("model",row.model());result.put("issues",row.issues());result.put("moldSetId",match.moldSetId());result.put("candidates",match.candidates());result.put("reason",match.reason());out.add(result);if(match.moldSetId()!=null)unique.add(match.moldSetId());}
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("actual-match-audit.json").toFile(),out);
        assertThat(rows).hasSize(42);assertThat(out).allSatisfy(row->{assertThat((List<?>)row.get("issues")).isEmpty();assertThat(row.get("moldSetId")).as(row.toString()).isNotNull();});assertThat(unique).hasSize(42);
        assertThat(out.stream().filter(r->Objects.equals(r.get("rowNo"),24)).findFirst().orElseThrow().get("moldSetId")).isEqualTo(598L);
        if(Files.exists(live)){var raw=json.readTree(live.toFile());var retained=MoldScheduleSheetParser.retainedSnapshot(raw);assertThat(MoldScheduleSheetParser.parse(retained)).isEqualTo(rows);assertThat(json.writeValueAsBytes(retained).length).isLessThan(60000);}
    }
}
