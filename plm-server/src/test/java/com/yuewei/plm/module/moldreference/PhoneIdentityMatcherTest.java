package com.yuewei.plm.module.moldreference;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneIdentityMatcherTest {
    @Test void accessoryWordsAreExplicitAndDoNotMatchAnUnrecognizedPhoneSubstring() {
        assertThat(PhoneIdentityMatcher.nonPhoneEvidence("Clip de telefonos c/base")).contains("Clip");
        assertThat(PhoneIdentityMatcher.nonPhoneEvidence("Eclipse 5G")).isNull();
        assertThat(PhoneIdentityMatcher.nonPhoneEvidence("TE Infinix teno pova")).isNull();
    }
    @Test void fullSubBrandInOldParentBrandNamesWinsWithoutTreatingRedmiSeriesAbbreviationsAsRealme() {
        var brands=List.of(brand(9,"Huawei","HW"),brand(6,"Honor"),brand(18,"OPPO","OP"),brand(21,"Realme","RM"),xiaomi);
        assertThat(PhoneIdentityMatcher.reviewBrand("OPPO Realme C75 4G",brands).brandIds()).containsExactly(21L);
        assertThat(PhoneIdentityMatcher.reviewBrand("HW Honor V8",brands).brandIds()).containsExactly(6L);
        assertThat(PhoneIdentityMatcher.reviewBrand("HW Honor 5C",brands).brandIds()).containsExactly(6L);
        assertThat(PhoneIdentityMatcher.reviewBrand("XM RM NOTE 17 PRO/NOTE 17 PRO MAX",brands).brandIds()).containsExactly(16L);
    }
    final Map<String,Object> apple=brand(5,"Apple","IP","iPhone","苹果"),xiaomi=brand(16,"XIAOMI","XM","小米"),huawei=brand(4,"Huawei","HW","华为"),samsung=brand(12,"Samsung","SM","三星");
    Map<String,Object> brand(long id,String name,String... aliases) {return Map.of("brandId",id,"name",name,"aliases",List.of(aliases),"status","enabled");}
    Map<String,Object> phone(long id,long brand,String model,String... aliases) {return Map.of("phoneModelId",id,"brandId",brand,"modelName",model,"aliases",List.of(aliases),"status","enabled","codeLinkStatus","linked");}
    PhoneIdentityMatcher.Match match(String brand,String model,Map<String,Object>... phones) {return PhoneIdentityMatcher.match(brand,model,List.of(apple,xiaomi,huawei,samsung),List.of(phones));}
    @Test void userConfirmedBrandAndSeriesPrefixesResolveOnlyWholeLeadingTokens() {
        assertThat(match("APPLE"," IP 18  PRO ",phone(1,5,"Apple iPhone 18 Pro")).phoneModelId()).isEqualTo(1L);
        assertThat(match("小米","XM POCO X8 PRO",phone(2,16,"Xiaomi POCO X8 Pro")).phoneModelId()).isEqualTo(2L);
        assertThat(match("HW","HW Nova 10 SE 4G",phone(3,4,"Huawei Nova 10 SE 4G")).phoneModelId()).isEqualTo(3L);
        assertThat(match("SM","SM A57 5G",phone(4,12,"Samsung Galaxy A57 5G")).phoneModelId()).isEqualTo(4L);
    }
    @Test void ipadHardwareCodesAndMidWordPrefixesAreNeverReplaced() {
        assertThat(match("APPLE","iPad 18 Pro",phone(1,5,"iPhone 18 Pro")).state()).isEqualTo("unmatched");
        assertThat(match("SM","SM-A57",phone(1,12,"Galaxy A57")).state()).isEqualTo("unmatched");
        assertThat(PhoneIdentityMatcher.modelKey("IPAD AIR 6 11inch",apple)).isEqualTo("ipad air 6 11inch");
        assertThat(PhoneIdentityMatcher.modelKey("POCO XM X8",xiaomi)).isEqualTo("poco xm x8");
    }
    @Test void variantsNetworkRegionAndHoleScopeAreNotErased() {
        var standard=phone(1,12,"Galaxy S26 Pro 5G Gran agujero MX");
        for(String model:List.of("SM S26 5G Gran agujero MX","SM S26 Pro 4G Gran agujero MX","SM S26 Pro 5G Agujero fino MX","SM S26 Pro 5G Gran agujero CN"))assertThat(match("SM",model,standard).state()).isEqualTo("unmatched");
        assertThat(match("IP","IP 18 Pro",phone(1,5,"iPhone 18 Pro Max")).state()).isEqualTo("unmatched");
    }
    @Test void productAnchoredAliasesAndWhitespaceResolveWithoutGloballyDroppingNetworkOrVariants() {
        var phone=new LinkedHashMap<>(phone(1,12,"SM Galaxy S25 Ultra 5G"));
        assertThat(match("Samsung","Galaxy S25 Ultra",phone).state()).isEqualTo("unmatched");
        phone.put("matchingAliases",List.of("Samsung Galaxy S25 Ultra"));
        assertThat(match("Samsung","Galaxy S25Ultra",phone).phoneModelId()).isEqualTo(1L);
        assertThat(match("Samsung","Galaxy S25 Pro",phone).state()).isEqualTo("unmatched");
        assertThat(match("Samsung","Galaxy S25 Ultra 4G",phone).state()).isEqualTo("unmatched");
        assertThat(match("Samsung","Galaxy S25 Ultra",phone,phone(2,12,"Galaxy S25 Ultra")).state()).isEqualTo("ambiguous");
    }
    @Test void aliasCollisionNeverPicksFirstAndDifferentPhonesSharingCodesRemainDistinct() {
        var first=phone(1,5,"iPhone 18 Pro","IP 18 P");var second=phone(2,5,"iPhone 18 Pro Max","IP 18 P");
        var result=match("APPLE","IP 18 P",first,second);assertThat(result.state()).isEqualTo("ambiguous");assertThat(result.phoneModelId()).isNull();assertThat(result.candidateIds()).containsExactly(1L,2L);
        assertThat(match("APPLE","iPhone 18 Pro",first,second).phoneModelId()).isEqualTo(1L);
    }
    @Test void groupedModelsAndUnsupportedRmExpansionStayForReview() {
        assertThat(match("XM","XM RM NOTE 17 PRO/NOTE 17 PRO MAX",phone(1,16,"Redmi Note 17 Pro")).state()).isEqualTo("unmatched");
        assertThat(match("XM","XM RM NOTE 17 PRO",phone(1,16,"Redmi Note 17 Pro")).state()).isEqualTo("unmatched");
    }
    @Test void anExplicitConflictingSourceBrandCannotBeInferredFromANamePrefix() {
        assertThat(match("SAMSUNG","IP 18 Pro",phone(1,5,"iPhone 18 Pro")).state()).isEqualTo("unmatched");
        assertThat(match("","IP 18 Pro",phone(1,5,"iPhone 18 Pro")).phoneModelId()).isEqualTo(1L);
    }
    @Test void disabledPhoneAndBrandDoNotCreateNewAutomaticAssociations() {
        var disabled=new LinkedHashMap<>(phone(1,5,"iPhone 18 Pro"));disabled.put("status","disabled");assertThat(match("APPLE","IP 18 Pro",disabled).state()).isEqualTo("unmatched");
        var brand=new LinkedHashMap<>(apple);brand.put("status","disabled");assertThat(PhoneIdentityMatcher.match("APPLE","IP 18 Pro",List.of(brand),List.of(phone(1,5,"iPhone 18 Pro"))).state()).isEqualTo("unmatched");
    }
    @Test void maintainedSeriesRulesChangeOnlyExactPrefixesAndCanBeDisabled() {
        var brand=new LinkedHashMap<>(xiaomi);brand.put("prefixes",List.of(Map.of("prefix","RM","replacement","Redmi")));
        assertThat(PhoneIdentityMatcher.match("XM","XM RM Note 17 Pro",List.of(brand),List.of(phone(8,16,"Redmi Note 17 Pro"))).phoneModelId()).isEqualTo(8L);
        assertThat(PhoneIdentityMatcher.modelKey("POCO RM Note 17 Pro",brand)).isEqualTo("poco rm note 17 pro");
        var empty=new LinkedHashMap<>(apple);empty.put("prefixes",List.of());assertThat(PhoneIdentityMatcher.modelKey("IP 18 Pro",empty)).isEqualTo("18 pro");
        assertThat(PhoneIdentityMatcher.normalize("Phone ①")).isNotEqualTo(PhoneIdentityMatcher.normalize("Phone 1"));
    }
    @Test void commasAndNewlinesAreCombinationReviewEvenWhenAnExactAliasExists() {
        for(String name:List.of("IP 17 Pro,18 Pro","IP 17 Pro，18 Pro","IP 17 Pro、18 Pro","IP 17 Pro\n18 Pro"))assertThat(match("APPLE",name,phone(1,5,"iPhone 18 Pro",name)).state()).isEqualTo("unmatched");
    }
    @Test void brandReviewClassifiesExplicitAbbreviationsWithoutRequiringPhoneIdentityOrChangingRawNames() {
        var oppo=brand(18,"OPPO","OP");var brands=List.of(apple,xiaomi,huawei,samsung,oppo);
        for(String original:List.of("OP RENO 15 5G/15F 5G"," op\u00a0RENO 15 Pro（大孔）")) {
            var result=PhoneIdentityMatcher.reviewBrand(original,brands);
            assertThat(result.state()).isEqualTo("ready");assertThat(result.brandIds()).containsExactly(18L);assertThat(result.evidence()).anyMatch(s->s.contains("op"));
        }
        assertThat(PhoneIdentityMatcher.reviewBrand("SM Galaxy A12/M12/F12/A12 Nacho",brands).brandIds()).containsExactly(12L);
        assertThat(PhoneIdentityMatcher.reviewBrand("XM RM NOTE 17 PRO/NOTE 17 PRO MAX",brands).brandIds()).containsExactly(16L);
        assertThat(PhoneIdentityMatcher.reviewBrand("IP 11/XR",brands).brandIds()).containsExactly(5L);
        assertThat(PhoneIdentityMatcher.reviewBrand("SM Note 10 Plus/+",brands).brandIds()).containsExactly(12L);
        assertThat(PhoneIdentityMatcher.match("OPPO","OP RENO 15 5G/15F 5G",brands,List.of()).state()).isEqualTo("unmatched");
    }
    @Test void brandReviewPreservesEveryExplicitBrandOfACombinationWithoutCreatingPhoneIdentity() {
        var brands=List.of(apple,xiaomi,huawei,samsung,brand(18,"OPPO","OP"),brand(17,"OnePlus","NP"),brand(19,"Realme","RM"),brand(20,"Honor"));
        assertThat(PhoneIdentityMatcher.reviewBrand("NP Nord N200 5G/OP A72 5G/OP A93 5G",brands).brandIds()).containsExactly(17L,18L);
        assertThat(PhoneIdentityMatcher.reviewBrand("RM 7 5G/V5 5G/OPPO K7x",brands).brandIds()).containsExactly(18L,19L);
        assertThat(PhoneIdentityMatcher.reviewBrand("HW Mate 9 Lite/Honor 6X/GR5 2017",brands).brandIds()).containsExactly(4L,20L);
        assertThat(PhoneIdentityMatcher.reviewBrand("SM S26、OP A93",brands).brandIds()).containsExactly(12L,18L);
        assertThat(PhoneIdentityMatcher.reviewBrand("SM S26\nIP 18 Pro",brands).brandIds()).containsExactly(5L,12L);
        assertThat(PhoneIdentityMatcher.reviewBrand("XM RM NOTE 17 PRO/NOTE 17 PRO MAX",brands).brandIds()).containsExactly(16L);
    }
    @Test void longestMaintainedSeriesPrefixWinsOverAConflictingShortBrandAliasAndIpadNeverBecomesIphone() {
        var tecno=new LinkedHashMap<>(brand(15,"TECNO"));tecno.put("prefixes",List.of(Map.of("prefix","TC Spark","replacement","Spark"),Map.of("prefix","TC Camon","replacement","Camon")));
        var brands=List.of(brand(14,"TCL","TC"),tecno,brand(16,"Infinix"));
        assertThat(PhoneIdentityMatcher.reviewBrand("TC 10L",brands).brandIds()).containsExactly(14L);
        assertThat(PhoneIdentityMatcher.reviewBrand("TC Spark 20 Pro",brands).brandIds()).containsExactly(15L);
        assertThat(PhoneIdentityMatcher.reviewBrand("TC SPARK 20 PRO/INFINIX HOT30",brands).brandIds()).containsExactly(15L,16L);
        var ipad=new LinkedHashMap<>(apple);ipad.put("prefixes",List.of(Map.of("prefix","IP","replacement","iPhone"),Map.of("prefix","iPad","replacement","iPad")));
        assertThat(PhoneIdentityMatcher.reviewBrand("iPad 17",List.of(ipad)).brandIds()).containsExactly(5L);
        assertThat(PhoneIdentityMatcher.modelKey("iPad 17",ipad)).isNotEqualTo(PhoneIdentityMatcher.modelKey("IP 17",ipad));
    }
    @Test void brandReviewUsesMaintainedRulesAndExposesCollisionsUnknownAndIncompleteGroups() {
        var brand=new LinkedHashMap<>(samsung);brand.put("prefixes",List.of(Map.of("prefix","Galaxy","replacement","")));
        assertThat(PhoneIdentityMatcher.reviewBrand("Galaxy S26 5G",List.of(brand)).brandIds()).containsExactly(12L);
        assertThat(PhoneIdentityMatcher.sourceBrands("","Galaxy S26 5G",List.of(brand))).containsExactly(brand);
        var tcl=brand(14,"TCL","TC");var tecno=brand(15,"Tecno","TC");
        assertThat(PhoneIdentityMatcher.reviewBrand("TC Spark 20",List.of(tcl,tecno)).reason()).contains("多个品牌");
        assertThat(PhoneIdentityMatcher.reviewBrand("iPad 18 Pro",List.of(apple)).state()).isEqualTo("unresolved");
        assertThat(PhoneIdentityMatcher.reviewBrand("SM-A57",List.of(samsung)).state()).isEqualTo("unresolved");
        assertThat(PhoneIdentityMatcher.reviewBrand("UnknownPhone 9",List.of(samsung)).state()).isEqualTo("unresolved");
        assertThat(PhoneIdentityMatcher.reviewBrand("SM A57/",List.of(samsung)).reason()).contains("空白段");
        assertThat(PhoneIdentityMatcher.reviewBrand("SM A57/UnknownMaker Ultra",List.of(samsung)).reason()).contains("缺少可确认");
    }
}
