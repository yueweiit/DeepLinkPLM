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
    private Map<String,Object> entry(Object index,String model,String status,String note) {
        return new LinkedHashMap<>(Map.of("supplierIndex",index,"rawBrandGroup","Apple","rawModelText",model,"rawStatus",status,"rawNote",note));
    }
    @Test void preservesEverySupplierRowAndStatusWithoutInventingATrialDateOrStockFact() {
        var raw=List.of(entry(1,"IP17PRO/18PRO","现货","大孔"),entry(2,"IP99","开模中","20号试模"),entry(3,"IP98","22号试模",""));
        var entries=PartnerCatalogModelSheet.resolve(raw,resolver.newScope(),List.of());
        assertEquals(3,entries.size());assertEquals(List.of(12L,13L),entries.get(0).get("phoneModelIds"));
        for(int i=0;i<raw.size();i++)for(String field:List.of("supplierIndex","rawBrandGroup","rawModelText","rawStatus","rawNote"))assertEquals(raw.get(i).get(field),entries.get(i).get(field));
        assertEquals(2L,PartnerCatalogModelSheet.summary(entries).get("unresolvedEntryCount"));
        assertFalse(entries.get(1).containsKey("available"));assertFalse(entries.get(2).containsKey("trialDate"));
    }
    @Test void keepsMoreThanTwoThousandRawRowsAndDoesNotDropAnUnmatchedRow() {
        var raw=new ArrayList<Map<String,Object>>();for(int i=1;i<=2001;i++)raw.add(entry(i,"IP99","开模中","原文"));
        var result=PartnerCatalogModelSheet.resolve(raw,resolver.newScope(),List.of());assertEquals(2001,result.size());assertEquals(2001L,PartnerCatalogModelSheet.summary(result).get("unresolvedEntryCount"));
    }
    @Test void normalizationAndManualCombinedIdentityNeedExplicitEvidenceAndNeverCreateAMember() {
        var raw=entry(1,"IP 16E","开模中","");raw.put("matchText","iPhone 16e");
        assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of()));
        raw.put("matchNote","原型号16E人工核对已有16e/17e组合成员，仅本款");raw.put("phoneModelIds",List.of(14L));
        var result=PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of()).get(0);
        assertEquals("manual",result.get("matchMethod"));assertEquals("manual",result.get("matchState"));assertEquals(List.of(14L),result.get("phoneModelIds"));
        assertFalse(((List<?>)result.get("automaticIssues")).isEmpty());assertEquals(3,members.size());
        raw.put("phoneModelIds",List.of(999L));assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of()));
        raw.put("phoneModelIds",List.of(14L));members.get(2).put("status","disabled");assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of()));
    }
    @Test void rejectsDuplicateOriginalRecordsAndArbitraryCostOrIdentityFields() {
        var raw=entry(1,"IP17PRO","现货","");assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw,raw),resolver.newScope(),List.of()));
        raw.put("unitPrice",999);assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of()));
    }
    @Test void supplierNumberingCanRestartByBrandAndRepeatForDifferentOriginalModels() {
        var apple=entry(17L,"IP17PRO","现货","");var samsung=entry(17L,"S26","原图红字","");samsung.put("rawBrandGroup","Samsung");
        var sameBrand=entry(17L,"IP18PRO","计划开模","");
        var result=PartnerCatalogModelSheet.resolve(List.of(apple,samsung,sameBrand),resolver.newScope(),List.of());
        assertEquals(3,result.size());assertEquals(List.of(12L),result.get(0).get("phoneModelIds"));assertEquals(List.of(13L),result.get(2).get("phoneModelIds"));
        assertEquals("Samsung",result.get(1).get("rawBrandGroup"));assertEquals("原图红字",result.get(1).get("rawStatus"));
    }
    @Test void originalLeadingZerosAndUnnumberedSourceLocationsAreNotRenumbered() {
        var raw=List.of(entry("001","IP17PRO","现货","原表号001"),entry("1","IP17PRO","开模中","原表另有1"),
            entry("原无序号（Samsung栏111后第1行）","Z FOLD8","原图红字","原图无编号；仅位置标签"),
            entry("原无序号（Samsung栏111后第2行）","Z FOLD8 WIDE","原图红字","原图无编号；仅位置标签"));
        var result=PartnerCatalogModelSheet.resolve(raw,resolver.newScope(),List.of());assertEquals(4,result.size());
        for(int i=0;i<raw.size();i++)assertEquals(raw.get(i).get("supplierIndex"),result.get(i).get("supplierIndex"));
        assertNotEquals(PartnerCatalogModelSheet.entryIdentity(raw.get(0)),PartnerCatalogModelSheet.entryIdentity(raw.get(1)));
        assertEquals(PartnerCatalogModelSheet.entryIdentity(entry(1L,"IP17PRO","","")),PartnerCatalogModelSheet.entryIdentity(entry("1","IP17PRO","","")));
        assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(entry(" ","IP17PRO","","")),resolver.newScope(),List.of()));
    }
    @Test void unchangedTrustedAssociationsStayFrozenWhenOnlyAnotherOriginalEntryIsBeingMaintained() {
        var initial=entry(1,"IP99","原图红字","原备注");
        var saved=PartnerCatalogModelSheet.resolve(List.of(initial),resolver.newScope(),List.of());
        members.add(phone(99,"iPhone 99","0099"));var unchanged=new LinkedHashMap<>(initial);unchanged.put("phoneModelIds",List.of());
        var retained=PartnerCatalogModelSheet.resolve(List.of(unchanged),resolver.newScope(),saved);
        assertEquals(List.of(),retained.get(0).get("phoneModelIds"));assertEquals("exact",retained.get(0).get("matchMethod"));assertEquals("原备注",retained.get(0).get("rawNote"));assertEquals("",retained.get(0).get("matchNote"));
        unchanged.remove("phoneModelIds");assertEquals(List.of(99L),PartnerCatalogModelSheet.resolve(List.of(unchanged),resolver.newScope(),saved).get(0).get("phoneModelIds"));
        unchanged.put("phoneModelIds",List.of(99L));unchanged.put("matchText","iPhone 17 Pro");assertThrows(RuntimeException.class,()->PartnerCatalogModelSheet.resolve(List.of(unchanged),resolver.newScope(),saved));
    }
    @Test void laterDictionaryGrowthCannotSilentlyExpandAnUnresolvedFrozenEntry() {
        var saved=PartnerCatalogModelSheet.resolve(List.of(entry(1,"IP99","开模中","")),resolver.newScope(),List.of());
        members.add(phone(99,"iPhone 99","9999"));var projected=PartnerCatalogModelSheet.project(saved,resolver.newScope());
        assertEquals(List.of(),projected.get(0).get("phoneModelIds"));assertEquals("unmatched",projected.get(0).get("matchState"));
        assertEquals("supplier",PartnerCatalogPhoneResolver.supplierScopeMode(Map.of("supplierModelEntries",saved)));
    }
    @Test void sharedIdentityNeverCollapsesSupplierRowsOrChoosesTheMostOptimisticStatus() {
        var first=entry(1,"IP17PRO","现货","大孔");var second=entry(2,"IP17PRO/IP99","开模中","22号试模");
        second.put("phoneModelIds",List.of(12L,14L));second.put("matchNote","已核对已有组合身份，但99原文仍待核实，不能因此宣称现货");
        var entries=PartnerCatalogModelSheet.resolve(List.of(first,second),resolver.newScope(),List.of());
        assertEquals(2,entries.size());assertEquals("现货",entries.get(0).get("rawStatus"));assertEquals("开模中",entries.get(1).get("rawStatus"));
        assertTrue(entries.get(1).get("issues").toString().contains("99"));assertEquals("manual",entries.get(1).get("matchState"));
        assertEquals(1L,PartnerCatalogModelSheet.summary(entries).get("manualEntryCount"));assertEquals(1L,PartnerCatalogModelSheet.summary(entries).get("automaticIssueEntryCount"));
    }
    @Test void cancelledTableAssociationsCannotReappearFromAnOlderFreeTextSummary() {
        var raw=entry(1,"IP17PRO","开模中","");raw.put("phoneModelIds",List.of());raw.put("matchNote","工程撤销关联，保留原文待核对");
        var saved=PartnerCatalogModelSheet.resolve(List.of(raw),resolver.newScope(),List.of());
        var projected=resolver.newScope().resolve(Map.of("phoneModelIds",List.of(),"modelText","IP17PRO","supplierModelEntries",saved));
        assertEquals(List.of(),projected.get("phoneModelIds"));assertEquals("supplier",projected.get("phoneScopeMode"));
        assertEquals(List.of(12L),resolver.newScope().resolve(Map.of("modelText","IP17PRO")).get("phoneModelIds"));
    }
}
