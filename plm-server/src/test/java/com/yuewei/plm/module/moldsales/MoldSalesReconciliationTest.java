package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoldSalesReconciliationTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode decision(boolean codeBased) {
        var d=json.createObjectNode().put("styleCode","BA").put("phoneCode","0999").put("decision","linked").put("targetItemId",11);
        d.putArray("sourceNames").add("Old order text");d.putObject("targetBasis").put("modelName","Old display name").put("phoneCode","1199");
        if(codeBased)d.putObject("codeBasis").put("kind","mold_catalog").putArray("codes").addObject().put("sourceCodeId",1).put("moldCode","MBA101199");
        return d;
    }
    MoldSalesReconciliation load(JsonNode d,List<Map<String,Object>> sources) throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(startsWith("select decisions::text"),eq(String.class))).thenReturn(List.of(json.writeValueAsString(List.of(d))));
        when(jdbc.queryForList(startsWith("select source_code_id"))).thenReturn(sources);
        when(jdbc.queryForList(startsWith("select style_code,phone_code"))).thenReturn(List.of(Map.of("style_code","BA","phone_code","0999","names","[\"New spelling on the same code\"]")));
        return MoldSalesReconciliation.load(jdbc,json);
    }
    List<Map<String,Object>> group(String phone) {
        var g=new LinkedHashMap<String,Object>();g.put("styleCode","BA");g.put("phoneCodes",new TreeSet<>(Set.of(phone)));
        g.put("productionRecords",List.of(Map.of("itemId",11L,"modelName","Renamed display only","phoneCode",phone)));
        return new ArrayList<>(List.of(g));
    }
    @Test void verifiedCodeSurvivesOrderAndArchiveDisplayRenaming() throws Exception {
        var review=load(decision(true),List.of(Map.of("source_code_id",1L,"mold_code","MBA101199")));var groups=group("1199");review.apply(groups);
        assertThat((Collection<String>)groups.get(0).get("phoneCodes")).contains("0999","1199");
    }
    @Test void disabledMissingOrReassignedSourceCodesCannotKeepAnAlias() throws Exception {
        for(var sources:List.of(List.<Map<String,Object>>of(),List.<Map<String,Object>>of(Map.of("source_code_id",1L,"mold_code","MBA101198")),List.<Map<String,Object>>of(Map.of("source_code_id",1L,"mold_code","MHA101199")))){
            var review=load(decision(true),sources);var groups=group("1199");review.apply(groups);
            assertThat((Collection<String>)groups.get(0).get("phoneCodes")).doesNotContain("0999");
            var row=new LinkedHashMap<String,Object>(Map.of("styleCode","BA","phoneCode","0999"));review.explain(row);assertThat(row.get("reviewReason").toString()).contains("编码资料");
        }
    }
    @Test void targetCodeChangesStillInvalidateTheCodeBinding() throws Exception {
        var review=load(decision(true),List.of(Map.of("source_code_id",1L,"mold_code","MBA101199")));var groups=group("1200");review.apply(groups);
        assertThat((Collection<String>)groups.get(0).get("phoneCodes")).doesNotContain("0999");
    }
    @Test void nameOnlyLegacyReviewsRetainTheirOriginalSafeguard() throws Exception {
        var review=load(decision(false),List.of());var groups=group("1199");review.apply(groups);
        assertThat((Collection<String>)groups.get(0).get("phoneCodes")).doesNotContain("0999");
    }
}
