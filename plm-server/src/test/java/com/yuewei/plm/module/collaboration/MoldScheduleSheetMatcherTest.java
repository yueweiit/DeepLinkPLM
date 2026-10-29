package com.yuewei.plm.module.collaboration;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MoldScheduleSheetMatcherTest {
    static Map<String,Object> brand(String name,String... aliases){return Map.of("brandId",1L,"name",name,"aliases",List.of(aliases),"status","enabled","prefixes",List.of());}
    static Map<String,Object> set(long id,String model,String code,boolean copy){var s=new HashMap<String,Object>();s.put("moldSetId",id);s.put("styleCode",code);s.put("styleName","canonical");s.put("compatibleModels",model);if(copy)s.put("sourceMoldSetId",100L);return s;}
    static MoldScheduleSheetParser.SourceRow row(String model,String brand){return new MoldScheduleSheetParser.SourceRow(3,"key","source-style",brand,model,Map.of(),"",false,List.of());}
    @Test void declared_shared_model_spelling_uses_the_existing_set_without_splitting_or_creating(){var brands=List.of(brand("Apple","iPhone","IP"));var sets=List.of(set(1,"Apple iPhone 17 Pro / iPhone 18 Pro","WV",false),set(2,"Apple iPhone 17 Pro Max / iPhone 18 Pro Max","WV",false));var m=MoldScheduleSheetMatcher.match(row("IP17PRO/18PRO","iPhone"),sets,brands,Map.of("source-style","WV"));assertThat(m.moldSetId()).isEqualTo(1);}
    @Test void omitted_series_in_combinations_does_not_drop_network_or_pro_designations(){var brands=List.of(brand("OPPO","OP"));var sets=List.of(set(1,"OPPO Reno 16F 5G / Reno 16FS 5G","BA",false),set(2,"OPPO Reno 16 5G / Reno 16 Pro 5G","BA",false));assertThat(MoldScheduleSheetMatcher.match(row("OPPO RENO 16F 5G/16FS 5G","OPPO"),sets,brands,Map.of("source-style","BA")).moldSetId()).isEqualTo(1);assertThat(MoldScheduleSheetMatcher.match(row("OPPO RENO 16F 4G/16FS 4G","OPPO"),sets,brands,Map.of("source-style","BA")).moldSetId()).isNull();}
    @Test void a_replicated_tooling_set_stays_separate_from_the_original(){var sets=List.of(set(1,"Honor X8D 4G","BA",false),set(2,"Honor X8D 4G","BA",true));var brands=List.of(brand("Honor","HR"));assertThat(MoldScheduleSheetMatcher.match(row("Honor X8D 4G（重新开模，复制模具）","Honor"),sets,brands,Map.of("source-style","BA")).moldSetId()).isEqualTo(2);assertThat(MoldScheduleSheetMatcher.match(row("Honor X8D 4G","Honor"),sets,brands,Map.of("source-style","BA")).moldSetId()).isEqualTo(1);}
    @Test void real_conflicts_are_listed_instead_of_selecting_the_first(){var sets=List.of(set(1,"Honor X8D 4G","BA",false),set(2,"Honor X8D 4G","BA",false));var m=MoldScheduleSheetMatcher.match(row("Honor X8D 4G","Honor"),sets,List.of(brand("Honor","HR")),Map.of("source-style","BA"));assertThat(m.moldSetId()).isNull();assertThat(m.candidates()).containsExactly(1L,2L);}
}
