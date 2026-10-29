package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.yuewei.plm.module.partner.PartnerValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartnerCatalogModelSheetTest {
    private PartnerCatalogPhoneResolver resolver;
    private List<Map<String,Object>> members;
    @BeforeEach void setup() {
        var references = mock(PhoneModelReferenceService.class);
        members = new ArrayList<>(List.of(phone(12,"iPhone 17 Pro","0185"),phone(13,"iPhone 18 Pro","0185"),phone(14,"iPhone 16e/17e","1124")));
        when(references.matchingBrands()).thenReturn(List.of(Map.of("brandId",1L,"name","Apple","status","enabled")));
        when(references.matchingPhones()).thenAnswer(c -> members);
        resolver = new PartnerCatalogPhoneResolver(references);
    }
    private Map<String,Object> phone(long id,String name,String code) {
        return new LinkedHashMap<>(Map.of("phoneModelId",id,"brandId",1L,"modelName",name,"displayName",name,"phoneModelCode",code,"status","enabled","brandStatus","enabled","codeStatus","enabled","codeLinkStatus","linked"));
    }
    private Map<String,Object> entry(long index,String model,String status,String note) {
        return new LinkedHashMap<>(Map.of("supplierIndex",index,"rawBrandGroup","Apple","rawModelText",model,"rawStatus",status,"rawNote",note));
    }
    @Test void preservesEverySupplierRowAndStatusWithoutInventingATrialDateOrStockFact() {
        var raw=List.of(entry(1,"IP17PRO/18PRO","现货","大孔"),entry(2,"IP99","开模中","20号试模"),entry(3,"IP98","22号试模",""));
        var entries=PartnerCatalogModelSheet.resolve(raw,resolver.newScope());
        assertEquals(3,entries.size());assertEquals(List.of(12L,13L),entries.get(0).get("phoneModelIds"));
        for(int i=0;i<raw.size();i++)for(String field:List.of("supplierIndex","rawBrandGroup","rawModelText","rawStatus","rawNote"))assertEquals(raw.get(i).get(field),entries.get(i).get(field));
        assertEquals(2L,PartnerCatalogModelSheet.summary(entries).get("unresolvedEntryCount"));
        assertFalse(entries.get(1).containsKey("available"));assertFalse(entries.get(2).containsKey("trialDate"));
    }
    @Test void keepsMoreThanTwoThousandRawRowsAndDoesNotDropAnUnmatchedRow() {
        var raw=new ArrayList<Map<String,Object>>();for(int i=1;i<=2001;i++)raw.add(entry(i,"IP99","开模中","原文"));
        var result=PartnerCatalogModelSheet.resolve(raw,resolver.newScope());assertEquals(2001,result.size());assertEquals(2001L,PartnerCatalogModelSheet.summary(result).get("unresolvedEntryCount"));
    }
    @Test void normalizationAndManualCombinedIdentityNeedExplicitEvidenceAndNeverCreateAMember() {
        var raw=entry(1,"IP 16E","开模中","");raw.put("matchText","iPhone 16e");
        assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope()));
        raw.put("matchNote","原型号16E人工核对已有16e/17e组合成员，仅本款");raw.put("phoneModelIds",List.of(14L));
        var result=PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope()).get(0);
        assertEquals("manual",result.get("matchMethod"));assertEquals("manual",result.get("matchState"));assertEquals(List.of(14L),result.get("phoneModelIds"));
        assertFalse(((List<?>)result.get("automaticIssues")).isEmpty());assertEquals(3,members.size());
        raw.put("phoneModelIds",List.of(999L));assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope()));
        raw.put("phoneModelIds",List.of(14L));members.get(2).put("status","disabled");assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope()));
    }
    @Test void rejectsDuplicateSourceIndicesAndArbitraryCostOrIdentityFields() {
        var raw=entry(1,"IP17PRO","现货","");assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw,raw),resolver.newScope()));
        raw.put("unitPrice",999);assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope()));
    }
    @Test void laterDictionaryGrowthCannotSilentlyExpandAnUnresolvedFrozenEntry() {
        var saved=PartnerCatalogModelSheet.resolve(List.of(entry(1,"IP99","开模中","")),resolver.newScope());
        members.add(phone(99,"iPhone 99","9999"));var projected=PartnerCatalogModelSheet.project(saved,resolver.newScope());
        assertEquals(List.of(),projected.get(0).get("phoneModelIds"));assertEquals("unmatched",projected.get(0).get("matchState"));
        assertEquals("supplier",PartnerCatalogPhoneResolver.supplierScopeMode(Map.of("supplierModelEntries",saved)));
    }
    @Test void sharedIdentityNeverCollapsesSupplierRowsOrChoosesTheMostOptimisticStatus() {
        var first=entry(1,"IP17PRO","现货","大孔");var second=entry(2,"IP17PRO/IP99","开模中","22号试模");
        second.put("phoneModelIds",List.of(12L,14L));second.put("matchNote","已核对已有组合身份，但99原文仍待核实，不能因此宣称现货");
        var entries=PartnerCatalogModelSheet.resolve(List.of(first,second),resolver.newScope());
        assertEquals(2,entries.size());assertEquals("现货",entries.get(0).get("rawStatus"));assertEquals("开模中",entries.get(1).get("rawStatus"));
        assertTrue(entries.get(1).get("issues").toString().contains("99"));assertEquals("manual",entries.get(1).get("matchState"));
        assertEquals(1L,PartnerCatalogModelSheet.summary(entries).get("manualEntryCount"));assertEquals(1L,PartnerCatalogModelSheet.summary(entries).get("automaticIssueEntryCount"));
    }
    @Test void cancelledTableAssociationsCannotReappearFromAnOlderFreeTextSummary() {
        var raw=entry(1,"IP17PRO","开模中","");raw.put("phoneModelIds",List.of());raw.put("matchNote","工程撤销关联，保留原文待核对");
        var saved=PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope());
        var projected=resolver.newScope().resolve(Map.of("phoneModelIds",List.of(),"modelText","IP17PRO","supplierModelEntries",saved));
        assertEquals(List.of(),projected.get("phoneModelIds"));assertEquals("supplier",projected.get("phoneScopeMode"));
        assertEquals(List.of(12L),resolver.newScope().resolve(Map.of("modelText","IP17PRO")).get("phoneModelIds"));
    }
}
