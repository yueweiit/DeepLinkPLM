package com.yuewei.plm.module.moldapplication;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MoldApplicationSubmissionPolicyTest {
    private final Map<String,Object> research=Map.of("product_type","product_line","development_mode","launch_v1");
    private MoldApplicationSubmissionPolicy policy(Map<String,Object> product,boolean trusted,List<Map<String,Object>> mappings) {
        return MoldApplicationSubmissionPolicy.of(1L,product,trusted,null,null,null,"亮甲Rainbow 3.0",List.of("亮甲Rainbow 3.0","骑士","亮甲Rainbow 3.0"),mappings);
    }
    @Test void onlyTrustedResearchSourceAndResearchProductPermitUncodedSubmission() {
        var p=policy(research,true,List.of());assertThat(p.mode()).isEqualTo("research");assertThat(p.requiresCodes()).isFalse();assertThat(p.requiresStandardPhone()).isFalse();
        assertThat(p.filingMissing()).contains("产品特定编码","标准手机型号","手机型号编码","正式模具编码","Tipo与产品及材质编码映射");
        for(var strict:List.of(policy(research,false,List.of()),policy(Map.of("product_type","model_variant","development_mode","launch_v1"),true,List.of()),policy(Map.of("product_type","product_line"),true,List.of()))) {
            assertThat(strict.mode()).isEqualTo("model");assertThat(strict.requiresCodes()).isTrue();assertThat(strict.requiresStandardPhone()).isTrue();
        }
    }
    @Test void officialOptionsAreDeduplicatedAndAnotherProductsMappedTipoIsExcluded() {
        var mapping=List.<Map<String,Object>>of(Map.of("tipo","骑士","productLineId",2L));
        assertThat(policy(research,true,mapping).allowedTipos()).containsExactly("亮甲Rainbow 3.0");
        assertThat(policy(research,false,mapping).allowedTipos()).isEmpty();
    }
}
