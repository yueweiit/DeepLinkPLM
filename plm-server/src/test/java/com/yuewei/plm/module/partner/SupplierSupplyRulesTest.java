package com.yuewei.plm.module.partner;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupplierSupplyRulesTest {
    Map<String,Object> fact(long phone,long color,String state){var r=new LinkedHashMap<String,Object>();r.put("supplierCode","A");r.put("styleKey","case");r.put("configKey","magnetic");r.put("quoteVersion","q1");r.put("phoneModelId",phone);r.put("colorId",color);r.put("state",state);r.put("evidence","供方清单核对");return r;}
    Map<String,Object> selected(long p,long c){return Map.of("phoneModelId",p,"colorId",c);}
    Map<String,Object> check(List<Map<String,Object>> facts,List<Map<String,Object>> selected){return SupplierSupplyRules.coverage("A","case","magnetic","q1",facts,selected);}
    @Test void twoListsNeverDeclareACartesianProductAndMissingPairRemainsUnknown(){var r=check(List.of(fact(1,10,"available"),fact(2,20,"available")),List.of(selected(1,20)));assertThat(r).containsEntry("canConfirm",false);assertThat((List<?>)r.get("unverified")).hasSize(1);}
    @Test void explicitlyUnprovidedAndUnverifiedAreDifferent(){var r=check(List.of(fact(1,10,"unavailable"),fact(2,10,"unverified")),List.of(selected(1,10),selected(2,10)));assertThat((List<?>)r.get("unavailable")).hasSize(1);assertThat((List<?>)r.get("unverified")).hasSize(1);}
    @Test void emptySelectedPairsCannotConfirmEvenWhenRangeReferencesExist(){assertThatThrownBy(()->SupplierSupplyRules.validate("A","case","magnetic","q1",List.of(fact(1,10,"available")),List.of())).hasMessageContaining("实际选择");}
    @Test void contradictoryStatesOrPricesDoNotResolveByLastRow(){var a=fact(1,10,"available");var b=fact(1,10,"available");a.put("unitPrice","1");b.put("unitPrice","2");assertThat(check(List.of(a,b),List.of(selected(1,10)))).containsEntry("canConfirm",false);assertThat(check(List.of(b,a),List.of(selected(1,10)))).containsEntry("canConfirm",false);}
    @Test void unselectedCandidateConflictDoesNotBlockTheChosenVerifiedScope(){assertThat(check(List.of(fact(1,10,"available"),fact(2,20,"available"),fact(2,20,"unavailable")),List.of(selected(1,10)))).containsEntry("canConfirm",true);}
    @Test void supplierStyleConfigurationAndQuoteVersionAreBound(){for(String field:List.of("supplierCode","styleKey","configKey","quoteVersion")){var value=fact(1,10,"available");value.put(field,"other");assertThatThrownBy(()->check(List.of(value),List.of(selected(1,10)))).isInstanceOf(RuntimeException.class);}}
    @Test void evidenceAndRealPairReferencesAreRequiredAndNoPriceOrCurrencyIsInvented(){var f=fact(1,10,"available");f.remove("evidence");assertThatThrownBy(()->check(List.of(f),List.of(selected(1,10)))).hasMessageContaining("依据");assertThatThrownBy(()->check(List.of(),List.of(selected(1,0)))).hasMessageContaining("真实");var good=SupplierSupplyRules.validate("A","case","magnetic","q1",List.of(fact(1,10,"available")),List.of(selected(1,10)));assertThat(good.toString()).doesNotContain("CNY");}
    @Test void duplicateSelectedPhysicalPairIsNotCountedTwice(){assertThatThrownBy(()->check(List.of(fact(1,10,"available")),List.of(selected(1,10),selected(1,10)))).hasMessageContaining("重复");}
    @Test void unquotedFactsRequireTheExactFrozenSourceContextAndStillRequireEvidence(){
        var f=fact(1,10,"available");f.put("quoteVersion","");String source="selection:2:real-row:5:3";f.put("sourceContext",source);
        assertThat(SupplierSupplyRules.validate("A","case","magnetic","",List.of(f),List.of(selected(1,10)),source)).containsEntry("canConfirm",true);
        for(String context:List.of("","selection:2:real-row:6:3","selection:9:other-row:5:3"))assertThatThrownBy(()->SupplierSupplyRules.validate("A","case","magnetic","",List.of(f),List.of(selected(1,10)),context)).hasMessageContaining("来源版本");
        f.remove("evidence");assertThatThrownBy(()->SupplierSupplyRules.validate("A","case","magnetic","",List.of(f),List.of(selected(1,10)),source)).hasMessageContaining("依据");
    }
    @Test void aSourceContextNeverOverridesAFormalQuoteVersionOrItsSupplierAndConfiguration(){
        var f=fact(1,10,"available");f.put("sourceContext","selection:2:row:5:3");f.put("quoteVersion","wrong");assertThatThrownBy(()->SupplierSupplyRules.validate("A","case","magnetic","q1",List.of(f),List.of(selected(1,10)),"selection:2:row:5:3")).hasMessageContaining("报价版本");
        f.put("quoteVersion","");for(String key:List.of("supplierCode","styleKey","configKey")){var changed=new LinkedHashMap<>(f);changed.put(key,"another");assertThatThrownBy(()->SupplierSupplyRules.validate("A","case","magnetic","",List.of(changed),List.of(selected(1,10)),"selection:2:row:5:3")).isInstanceOf(RuntimeException.class);}
    }
}
