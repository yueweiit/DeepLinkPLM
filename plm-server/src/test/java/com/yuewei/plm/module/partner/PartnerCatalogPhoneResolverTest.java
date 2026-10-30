package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Source text can only select existing members; catalogue matching never creates a code or product. */
class PartnerCatalogPhoneResolverTest {
    private PhoneModelReferenceService references;
    private PartnerCatalogPhoneResolver resolver;
    private List<Map<String,Object>> phones;
    private List<Map<String,Object>> brands;

    @BeforeEach void setup() {
        references = mock(PhoneModelReferenceService.class);
        resolver = new PartnerCatalogPhoneResolver(references);
        brands = new ArrayList<>(List.of(values("brandId", 10L, "name", "Apple", "aliases", List.of("苹果"), "status", "enabled",
            "prefixes", List.of(Map.of("prefix", "IP", "replacement", "iPhone"), Map.of("prefix", "iPhone", "replacement", "iPhone"))),
            values("brandId", 20L, "name", "Samsung", "aliases", List.of("三星"), "status", "enabled")));
        phones = new ArrayList<>();
        when(references.matchingBrands()).thenAnswer(a -> brands);
        when(references.matchingPhones()).thenAnswer(a -> phones);
    }

    @Test void confirmedGenerationRangeExpandsExistingSubmodelsAndExcludesMini() {
        for (int generation=11; generation<=18; generation++) {
            phones.add(phone(generation*10L, "iPhone " + generation, "0" + generation + "0", 10L));
            phones.add(phone(generation*10L+1, "IP " + generation + " PRO MAX", "0" + generation + "1", 10L));
        }
        phones.add(phone(129L, "iPhone 12 mini", "0129", 10L));
        phones.add(phone(190L, "iPhone 19 Pro", "0190", 10L));
        var row = resolve("iPhone11到18系列（mini除外）");
        assertEquals(16, ids(row).size()); assertFalse(ids(row).contains(129L)); assertFalse(ids(row).contains(190L));
        assertEquals("matched", summary(row).get("state")); assertEquals(List.of(), row.get("phoneMatchIssues"));
        assertEquals("0181", maps(row.get("phones")).get(0).get("phoneModelCode"));
    }

    @Test void explicitPhoneTextDoesNotExpandTheWholeGeneration() {
        phones.add(phone(1, "iPhone 18", "0001", 10L)); phones.add(phone(2, "iPhone 18 Pro Max", "0185", 10L));
        var row = resolve("iPhone18ProMax");
        assertEquals(List.of(2L), ids(row)); assertEquals("0185", maps(row.get("phones")).get(0).get("phoneModelCode"));
    }

    @Test void shorthandCombinationReusesAllIndependentSharedCodeMembers() {
        phones.add(phone(1, "IP 17 PRO", "1297", 10L)); phones.add(phone(2, "iPhone 18 Pro", "1297", 10L));
        phones.add(phone(3, "IP 17 PRO MAX", "1296", 10L)); phones.add(phone(4, "iPhone 18 Pro Max", "1296", 10L));
        var row = resolve("17PRO/IP18PRO/17PRO MAX/IP18PRO MAX");
        assertEquals(Set.of(1L,2L,3L,4L), new HashSet<>(ids(row)));
        assertEquals(4, summary(row).get("matchedCount")); assertEquals(2L, summary(row).get("codeGroupCount"));
    }

    @Test void maintainedAliasesAreExactAndCertainPartsSurviveAnAmbiguousPart() {
        var a=phone(1,"Samsung Galaxy S24","0001",20L); a.put("aliases",List.of("Galaxy shared","供方叫法")); phones.add(a);
        var b=phone(2,"Samsung Galaxy S25","0002",20L); b.put("aliases",List.of("Galaxy shared")); phones.add(b);
        var exact=phone(3,"Samsung Galaxy S26","0003",20L); phones.add(exact);
        var row=resolver.resolveAll(List.of(values("modelText","供方叫法 / Galaxy shared / Galaxy S26", "brandName","Samsung"))).get(0);
        assertEquals(List.of(3L,1L),ids(row)); assertEquals("partial",summary(row).get("state"));
        assertTrue(row.get("phoneMatchIssues").toString().contains("多个独立资料"));
    }

    @Test void exactSupplierModelPrefersItsSingleModelCodeWithoutChangingExplicitHistoricChoices() {
        var group=phone(1,"Samsung Galaxy S25 Plus 5G","1087",20L);
        group.put("originalCodeName","SM Galaxy S24 Plus/S25 Plus 5G");phones.add(group);
        var single=phone(2,"Samsung Galaxy S25 Plus 5G","1110",20L);
        single.put("originalCodeName","SM Galaxy S25 Plus 5G");phones.add(single);
        assertEquals(List.of(2L),ids(resolve("Samsung Galaxy S25 Plus 5G")));
        assertEquals(List.of(1L),ids(resolver.resolveAll(List.of(values("phoneModelIds",List.of(1L),"modelText","Samsung Galaxy S25 Plus 5G"))).get(0)));
        assertEquals("1087",group.get("phoneModelCode"));assertEquals("1110",single.get("phoneModelCode"));
    }

    @Test void singleModelPreferenceDoesNotChooseBetweenMultipleSinglesAndKeepsAUniqueGroupFallback() {
        var group=phone(1,"Samsung Galaxy S25 Plus 5G","1087",20L);
        group.put("originalCodeName","SM Galaxy S24 Plus/S25 Plus 5G");phones.add(group);
        assertEquals(List.of(1L),ids(resolve("Samsung Galaxy S25 Plus 5G")));
        phones.add(phone(2,"Samsung Galaxy S25 Plus 5G","1110",20L));
        phones.add(phone(3,"Samsung Galaxy S25 Plus 5G","1111",20L));
        var row=resolve("Samsung Galaxy S25 Plus 5G");
        assertEquals(List.of(),ids(row));assertTrue(row.get("phoneMatchIssues").toString().contains("多个独立资料"));
    }

    @Test void explicitHistoricIdentitiesRemainAuthoritativeAndDisabledOnesAreNotReplaced() {
        var disabled=phone(1,"iPhone 11","0185",10L); disabled.put("status","disabled"); phones.add(disabled);
        phones.add(phone(2,"iPhone 18 Pro Max","1297",10L));
        var input=values("phoneModelIds",List.of(1L),"modelText","iPhone18ProMax");
        var row=resolver.resolveAll(List.of(input)).get(0);
        assertEquals(List.of(1L),ids(row)); assertEquals("disabled",maps(row.get("phones")).get(0).get("status"));
        assertEquals("partial",summary(row).get("state")); assertFalse(input.containsKey("phones"));
    }

    @Test void brokenHistoricIdentityIsVisibleAndNeverSilentlyDeletedByARead() {
        var row=resolver.resolveAll(List.of(values("phoneModelIds",List.of(991L),"modelText","iPhone18ProMax"))).get(0);
        assertEquals(List.of(991L),ids(row)); assertEquals(List.of(),row.get("phones"));
        assertTrue(row.get("phoneMatchIssues").toString().contains("991"));
    }

    @Test void newAutomaticMatchesExcludeDisabledMemberBrandAndCodeButNotDiscontinuedProduction() {
        phones.add(phone(1,"iPhone 18 Pro Max","1297",10L)); phones.get(0).put("productionStatus","discontinued");
        var code=phone(2,"iPhone 18 Pro","1296",10L); code.put("codeLinkStatus","disabled"); phones.add(code);
        var member=phone(3,"iPhone 17 Pro","1295",10L); member.put("status","disabled"); phones.add(member);
        brands.get(1).put("status","disabled"); phones.add(phone(4,"Samsung Galaxy S26","1294",20L));
        var row=resolve("iPhone18ProMax / IP18PRO / IP17PRO / Samsung Galaxy S26");
        assertEquals(List.of(1L),ids(row)); assertEquals("discontinued",maps(row.get("phones")).get(0).get("productionStatus"));
        assertEquals("partial",summary(row).get("state"));
    }

    @Test void missingSourceIsEmptyAndGenericAppleSeriesNeverSelectsEveryPhone() {
        phones.add(phone(1,"iPhone 18 Pro Max","1297",10L));
        assertEquals(List.of(),ids(resolve(""))); assertEquals("empty",summary(resolve("")).get("state"));
        var row=resolve("苹果系列"); assertEquals(List.of(),ids(row)); assertEquals("unmatched",summary(row).get("state"));
        assertEquals(List.of(),ids(resolve("18"))); // No Apple evidence: do not infer a brand from a bare number.
    }

    @Test void unsupportedOrMissingGenerationsReportOnlyTheGapAndNeverSubstituteANearbyGeneration() {
        phones.add(phone(1,"iPhone 17 Pro","1297",10L)); phones.add(phone(2,"iPhone 18 Pro","1296",10L));
        var range=resolve("iPhone16-18"); assertEquals(List.of(1L,2L),ids(range));
        assertEquals("partial",summary(range).get("state")); assertTrue(range.get("phoneMatchIssues").toString().contains("iPhone 16"));
        var wrong=resolve("IP16PRO"); assertEquals(List.of(),ids(wrong));
        assertEquals(List.of(),ids(resolve("iPhone18到11")));
    }

    @Test void singleCallAndScopedPagedReadsLoadTheWholeDictionaryOnceWithoutFirstPageTruncation() {
        for(int n=1;n<=205;n++) phones.add(phone(n,"型号"+n,String.format("%04d",n),20L));
        var scope=resolver.newScope();
        assertEquals(List.of(205L),ids(scope.resolve(values("brandName","Samsung","modelText","型号205"))));
        assertEquals(List.of(1L),ids(scope.resolveAll(List.of(values("brandName","Samsung","modelText","型号1"))).get(0)));
        verify(references,times(1)).matchingBrands(); verify(references,times(1)).matchingPhones();
        phones.add(phone(206,"型号206","0206",20L));
        assertEquals(List.of(),ids(scope.resolve(values("brandName","Samsung","modelText","型号206"))));
        assertEquals(List.of(206L),ids(resolver.resolveAll(List.of(values("brandName","Samsung","modelText","型号206"))).get(0)));
        verify(references,times(2)).matchingBrands(); verify(references,times(2)).matchingPhones();
    }

    @Test void sourceProjectionDoesNotLeakSourceSnapshotsOrMutateSharedPhoneMembers() {
        var phone=phone(1,"iPhone 18 Pro Max","0185",10L); phone.put("sourceSnapshot","private source"); phones.add(phone);
        var row=resolve("IP18PROMAX");
        assertFalse(maps(row.get("phones")).get(0).containsKey("sourceSnapshot"));
        assertEquals("private source",phone.get("sourceSnapshot"));
        verify(references).matchingBrands(); verify(references).matchingPhones(); verifyNoMoreInteractions(references);
    }

    @Test void supplierProjectionUsesTheFullNameAndKeepsFactoryAndMemberOriginalsSeparate() {
        var phone=phone(1,"SM A57","1210",20L);
        phone.put("displayName","Samsung Galaxy A57");phone.put("factoryName","SM A57");phones.add(phone);
        var row=resolver.resolveAll(List.of(values("phoneModelIds",List.of(1L),"modelText","A57"))).get(0);
        var projected=maps(row.get("phones")).get(0);
        assertEquals("Samsung Galaxy A57",projected.get("modelName"));
        assertEquals("SM A57",projected.get("originalModelName"));assertEquals("SM A57",projected.get("factoryName"));
        assertEquals("1210",projected.get("phoneModelCode"));assertEquals(1L,projected.get("phoneModelId"));
        assertEquals("SM A57",phone.get("modelName"));assertEquals("A57",row.get("modelText"));
    }

    @Test void explicitNegativeRangesNeverBecomePositiveMemberships() {
        phones.add(phone(1,"iPhone 11 Pro","0011",10L)); phones.add(phone(2,"iPhone 12 Pro","0012",10L));
        for(String source:List.of("iPhone11-12不做","不供iPhone11到12","iPhone11-12，部分型号不支持")) {
            var row=resolve(source); assertEquals(List.of(),ids(row),source); assertFalse(((List<?>)row.get("phoneMatchIssues")).isEmpty(),source);
        }
    }

    @Test void explicitExcludedGenerationsAreSubtractedWithoutLosingTheCertainRange() {
        phones.add(phone(1,"iPhone 11 Pro","0011",10L)); phones.add(phone(2,"iPhone 12","0012",10L));
        phones.add(phone(3,"iPhone 12 Pro","0013",10L)); phones.add(phone(4,"iPhone 13 Pro","0014",10L));
        for(String source:List.of("iPhone11-13，不含iPhone12","iPhone11到13（iPhone12除外）", "iPhone11到13（除iPhone12外）"))
            assertEquals(List.of(4L,1L),ids(resolve(source)),source);
        assertEquals(List.of(4L,2L,1L),ids(resolve("iPhone11到13，不含iPhone12Pro")));
    }

    @Test void negativeListsKeepTheirContextAndCannotTurnTrailingModelsBackIntoPositiveMatches() {
        phones.add(phone(1,"iPhone 11 Pro","0011",10L)); phones.add(phone(2,"iPhone 12 Pro","0012",10L));
        phones.add(phone(3,"iPhone 13 Pro","0013",10L)); phones.add(phone(4,"iPhone 14 Pro","0014",10L));
        for(String source:List.of("iPhone11-14，不含iPhone12/13","iPhone11-14，不含iPhone12,13", "iPhone11-14，iPhone12、13除外"))
            assertEquals(List.of(4L,1L),ids(resolve(source)),source);
    }

    @Test void aKnownSamsungFamilyCanOmitItsBrandButCannotGuessAnUnrelatedFamily() {
        phones.add(phone(1,"Samsung Galaxy S22","0022",20L)); phones.add(phone(2,"Samsung Galaxy S23","0023",20L));
        phones.add(phone(3,"Samsung Galaxy A24","0024",20L));
        assertEquals(List.of(2L,1L),ids(resolve("Samsung S22/S23")));
        var mixed=resolve("Samsung S22/A24"); assertEquals(List.of(1L),ids(mixed));
        assertEquals("partial",summary(mixed).get("state"));
    }

    @Test void anUnparsedSupplyDenialCannotLeaveAnExcludedExactModelSelected() {
        phones.add(phone(1,"Samsung Galaxy S22","0022",20L)); phones.add(phone(2,"Samsung Galaxy S23","0023",20L));
        var row=resolve("Samsung S22/S23，不做S22");
        assertEquals(List.of(),ids(row)); assertTrue(row.get("phoneMatchIssues").toString().contains("供货否定"));
    }

    @Test void realVerifiedFamilyRetainsAllThirtyNineExistingMembersAndThirtySevenCodeGroups() throws Exception {
        var fixture=realAppleFixture(); var expected=new LinkedHashSet<Long>();
        fixture.get("members").forEach(member->expected.add(member.path("id").asLong()));
        var row=resolve(fixture.path("sourceText").asText());
        assertEquals(expected,new LinkedHashSet<>(ids(row)));
        assertEquals(39,summary(row).get("matchedCount")); assertEquals(37L,summary(row).get("codeGroupCount"));
        assertEquals(List.of(),row.get("phoneMatchIssues"));
        var rendered=new LinkedHashMap<Long,String>(); maps(row.get("phones")).forEach(p->rendered.put(((Number)p.get("phoneModelId")).longValue(),p.get("modelName").toString()));
        assertEquals("IP 11/XR",rendered.get(1151L)); assertFalse(ids(row).contains(714L));
        assertEquals("IP 16 Pro Max Agujero fino",rendered.get(1290L));
        assertEquals("IP 13/14 Gran agujero",rendered.get(1152L));
        for(long mini:List.of(1150L,39L,7L)) assertFalse(ids(row).contains(mini));
        assertFalse(ids(row).contains(1321L));
    }

    @Test void exactModelsDoNotBorrowCombinedMembersOrSilentlyCollapseHoleVariants() throws Exception {
        realAppleFixture();
        assertEquals(List.of(141L),ids(resolve("iPhone16ProMax")));
        assertEquals(List.of(1290L),ids(resolve("IP 16 Pro Max Agujero fino")));
        assertEquals(List.of(40L),ids(resolve("iPhone13"))); // Not the independent 13/14/15 member.
        var ambiguous=resolve("iPhone14ProMax"); // Two independently maintained members share 0743.
        assertEquals(List.of(),ids(ambiguous)); assertEquals("unmatched",summary(ambiguous).get("state"));
        assertTrue(ambiguous.get("phoneMatchIssues").toString().contains("多个独立资料"));
    }

    @Test void explicitExclusionsAlsoCoverExistingCombinedAndHoleLabelledMembersWithoutSplittingIds() throws Exception {
        realAppleFixture();
        var noTwelve=resolve("iPhone11-18系列（mini除外），不含iPhone12");
        for(long excluded:List.of(1153L,461L,460L,564L,647L,9L)) assertFalse(ids(noTwelve).contains(excluded));
        assertTrue(ids(noTwelve).contains(818L)); assertFalse(ids(noTwelve).contains(714L));
        var noProMax=resolve("iPhone11-18系列（mini除外），不含iPhone16ProMax");
        assertFalse(ids(noProMax).contains(1290L)); assertFalse(ids(noProMax).contains(141L));
        assertTrue(ids(noProMax).contains(1289L));
        assertFalse(ids(resolve("iPhone11-18系列（mini除外），不含iPhone17e")).contains(828L));
    }

    private com.fasterxml.jackson.databind.JsonNode realAppleFixture() throws Exception {
        try(var source=getClass().getResourceAsStream("/fixtures/partner-catalog-apple-members-20261022.json")) {
            assertNotNull(source); var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(source);
            for(String key:List.of("members","outsideOrExcludedMembers")) for(var p:fixture.get(key))
                phones.add(phone(p.path("id").asLong(),p.path("name").asText(),p.path("code").asText(),10L));
            return fixture;
        }
    }

    private Map<String,Object> resolve(String text) {return resolver.resolveAll(List.of(values("modelText",text))).get(0);}
    private Map<String,Object> phone(long id,String name,String code,long brandId) {
        return values("id",id,"phoneModelId",id,"brandId",brandId,"brandName",brandId==10?"Apple":"Samsung",
            "modelName",name,"displayName",name,"phoneModelCode",code,"codeItemId",Long.parseLong(code)+10000,
            "status","enabled","brandStatus","enabled","codeStatus","enabled","codeLinkStatus","linked",
            "productionStatus","unknown","aliases",List.of());
    }
    @SuppressWarnings("unchecked") private static List<Long> ids(Map<String,Object> row) {return (List<Long>)row.get("phoneModelIds");}
    @SuppressWarnings("unchecked") private static Map<String,Object> summary(Map<String,Object> row) {return (Map<String,Object>)row.get("phoneMatchSummary");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) {return (List<Map<String,Object>>)value;}
    private static Map<String,Object> values(Object... pairs) {var m=new LinkedHashMap<String,Object>();for(int n=0;n<pairs.length;n+=2)m.put(pairs[n].toString(),pairs[n+1]);return m;}
}
