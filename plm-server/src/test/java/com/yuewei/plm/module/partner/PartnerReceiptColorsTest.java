package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartnerReceiptColorsTest {
 private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 private final PartnerReceiptColors colors=new PartnerReceiptColors(new PartnerStore(jdbc,new ObjectMapper(),mock(PilotAccessService.class),mock(OperationLogService.class)));
 private Map<String,Object> source(){return new LinkedHashMap<>(Map.of("supplierCode","SUP-A","styleKey","case-A","configKey","structure-1","receiptColorMode","at_receipt","selectedPairs",List.of(),"receiptColors",List.of(Map.of("id",8L,"name","黑色","code","SC-000008"),Map.of("id",9L,"name","Blue","code","SC-000009"))));}
 @Test void directExistingNameUsesItsRealIdWithoutAnyDatabaseMutation(){var resolved=colors.resolve(source(),12,Map.of("colorName","  blue  "));assertThat(resolved.id()).isEqualTo(9);assertThat(resolved.name()).isEqualTo("Blue");assertThat(resolved.proposed()).isFalse();verifyNoInteractions(jdbc);}
 @Test void explicitOldIdStillWorksWithoutANameButCannotRenameTheOriginalColour(){assertThat(colors.resolve(source(),12,Map.of("colorId",8)).name()).isEqualTo("黑色");assertThatThrownBy(()->colors.resolve(source(),12,Map.of("colorId",8,"colorName","蓝色"))).hasMessageContaining("不一致");verifyNoInteractions(jdbc);}
 @Test void genuinelyNewTextIsOnlyAProposalAndDoesNotAllocateAnIdentity(){var result=colors.resolve(source(),12,Map.of("colorName","银黑渐变"));assertThat(result.id()).isZero();assertThat(result.name()).isEqualTo("银黑渐变");assertThat(result.proposed()).isTrue();verify(jdbc).queryForList(startsWith("select color_id"),eq("SUP-A"),eq("银黑渐变"),eq("case-A"),eq("structure-1"));verifyNoMoreInteractions(jdbc);}
 @Test void sameNameDifferentRealColoursRequiresAnInlineChoice(){var s=source();s.put("receiptColors",List.of(Map.of("id",8,"name","黑色","supplierColorCode","B1"),Map.of("id",9,"name","黑色","supplierColorCode","B2")));assertThatThrownBy(()->colors.resolve(s,12,Map.of("colorName","黑色"))).hasMessageContaining("本行选择");assertThat(colors.resolve(s,12,Map.of("colorId",9,"colorName","黑色")).id()).isEqualTo(9);verifyNoInteractions(jdbc);}
 @Test void noFuzzyNameTranslationOrCombinationSplittingIsPerformed(){assertThat(colors.resolve(source(),12,Map.of("colorName","黑色/银色")).name()).isEqualTo("黑色/银色");assertThat(colors.resolve(source(),12,Map.of("colorName","蓝色")).proposed()).isTrue();}
 @Test void historicalConfirmedPairsKeepTheirOriginalPhoneAndColourScope(){var s=source();s.put("selectedPairs",List.of(Map.of("phoneModelId",12,"colorId",8,"colorName","历史黑"),Map.of("phoneModelId",13,"colorId",9,"colorName","另一型号蓝")));assertThat(colors.resolve(s,12,Map.of("colorName","历史黑")).id()).isEqualTo(8);assertThatThrownBy(()->colors.resolve(s,12,Map.of("colorName","新蓝"))).hasMessageContaining("原选样确认范围");assertThatThrownBy(()->colors.resolve(s,12,Map.of("colorId",9))).hasMessageContaining("已选范围");verifyNoInteractions(jdbc);}
 @Test void missingOrPastedMultilineColoursStayValidationErrors(){for(String value:List.of("","   ","图片色","如图","黑色\n蓝色","黑\t蓝","x".repeat(256)))assertThatThrownBy(()->colors.resolve(source(),12,Map.of("colorName",value))).isInstanceOf(RuntimeException.class);verifyNoInteractions(jdbc);}
 @Test void allColourSourceUsesOnlyTheFrozenRowBaselineNotTheSupplierCandidatesOrEditedDisplay(){
  var frozen=source();frozen.put("color","后续改色");frozen.put("supplierColors",List.of(Map.of("id",8,"name","通用候选")));
  frozen.put("catalogSourceSnapshot",Map.of("color","错误层级的原文","selectionBaseline",Map.of("color","原目录黑、原目录白")));
  assertThat(PartnerReceiptColors.sourceNames(frozen)).containsExactly("原目录黑","原目录白");verifyNoInteractions(jdbc);
 }
 @Test void explicitSourceNamesDeduplicateWithoutSplittingTwoToneColourNames(){
  var frozen=Map.<String,Object>of("catalogSourceSnapshot",Map.of("selectionBaseline",Map.of("color"," Black、black； 白色\n黑色/银色,Blue")));
  assertThat(PartnerReceiptColors.sourceNames(frozen)).containsExactly("Black","白色","黑色/银色","Blue");verifyNoInteractions(jdbc);
 }
 @Test void absentOrUnspecifiedOriginalColoursNeverTurnSuggestionsIntoAnAllColoursScope(){
  assertThat(PartnerReceiptColors.sourceNames(source())).isEmpty();
  for(String raw:List.of("待定","多色可选","见图","图片色","如图","黑色、其他颜色待确认","null","黑\t蓝","色".repeat(256))){
   var frozen=Map.<String,Object>of("catalogSourceSnapshot",Map.of("selectionBaseline",Map.of("color",raw)));
   assertThat(PartnerReceiptColors.sourceNames(frozen)).as(raw).isEmpty();
  }
  verifyNoInteractions(jdbc);
 }
 @Test void spacedChineseCatalogueColoursExpandToOneNamePerActualColour(){
  var frozen=Map.<String,Object>of("catalogSourceSnapshot",Map.of("selectionBaseline",Map.of("color","黑色 蓝色 灰蓝 墨绿 紫色 棕色")));
  assertThat(PartnerReceiptColors.sourceNames(frozen)).containsExactly("黑色","蓝色","灰蓝","墨绿","紫色","棕色");
  verifyNoInteractions(jdbc);
 }
 @Test void colourListsKeepTwoToneNamesAndEnglishWordBoundaries(){
  assertThat(PartnerReceiptColors.explicitNames("黑色 橙色+黑色、Light Blue; Rose Gold")).containsExactly("黑色","橙色+黑色","Light Blue","Rose Gold");
  assertThat(PartnerReceiptColors.explicitNames("黑色　蓝色 黑色")).containsExactly("黑色","蓝色");
  assertThat(PartnerReceiptColors.explicitNames("8色库存")).isEmpty();
  verifyNoInteractions(jdbc);
 }
 @Test void aColourListCannotBeSavedAsOneActualColourOrReusedThroughItsOldId(){
  assertThatThrownBy(()->colors.resolve(source(),12,Map.of("colorName","黑色 蓝色"))).hasMessageContaining("颜色");
  var frozen=source();frozen.put("receiptColors",List.of(Map.of("id",8,"name","黑色 蓝色")));
  assertThatThrownBy(()->colors.resolve(frozen,12,Map.of("colorId",8))).hasMessageContaining("颜色");
  verifyNoInteractions(jdbc);
 }
 @Test void receiptChoicesKeepRealAtomicIdsButDoNotOfferListsOrStockCountNotes(){
  var frozen=source();frozen.put("receiptColors",List.of(Map.of("id",8,"name","黑色"),Map.of("id",9,"name","Blue"),Map.of("id",10,"name","黑色 蓝色"),Map.of("id",11,"name","8色库存"),Map.of("id",12,"name","橙色+黑色")));
  assertThat(PartnerReceiptColors.choices(frozen,12)).extracting(row->row.get("id")).containsExactly(8,9,12);
  assertThat((List<?>)frozen.get("receiptColors")).hasSize(5);
  verifyNoInteractions(jdbc);
 }
 @Test void directCatalogSampleCreationAlsoRejectsAListInTheReferencedColour(){
  var frozen=source();frozen.put("catalogStyleId",1L);frozen.put("phoneModelIds",List.of(12L));frozen.put("phones",List.of(Map.of("phoneModelId",12L,"displayName","Phone12")));
  frozen.put("receiptColors",List.of(Map.of("id",8,"name","黑色 蓝色")));
  assertThatThrownBy(()->PartnerCatalogSampleRules.pair(frozen,12,8)).hasMessageContaining("颜色");
  frozen.put("receiptColors",List.of(Map.of("id",8,"name","橙色+黑色")));
  assertThat(PartnerCatalogSampleRules.pair(frozen,12,8).get("colorName")).isEqualTo("橙色+黑色");
  verifyNoInteractions(jdbc);
 }
}
