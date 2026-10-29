package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartnerFileSourceAccessTest {
 JdbcTemplate jdbc;PilotAccessService pilot;PartnerOperationsAccess access;PartnerStore store;PartnerFileSourceAccess sources;
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);pilot=mock(PilotAccessService.class);access=mock(PartnerOperationsAccess.class);store=spy(new PartnerStore(jdbc,new ObjectMapper(),pilot,mock(OperationLogService.class)));sources=new PartnerFileSourceAccess(store,access);}
 private void source(String kind,Map<String,Object> extra){when(jdbc.queryForList(startsWith("select kind from plm_partner_record"),eq(4L))).thenReturn(List.of(Map.of("kind",kind)));var head=new LinkedHashMap<String,Object>(extra);head.put("kind",kind);doReturn(head).when(store).load(eq(4L),eq(kind),anyBoolean());}
 @Test void legacySelectionUsesExistingObjectScopeButIsReadOnlyUntilItsPurposeIsExplicit(){source("selection",Map.of("status","draft"));assertThat(sources.read(4,false)).containsEntry("kind","selection");assertThatThrownBy(()->sources.write(4,true)).hasMessageContaining("用途尚未明确");verify(store).load(4,"selection",true);}
 @Test void anExplicitMySelectionRetainsTheNormalFileWritePath(){source("selection",Map.of("status","draft","purpose","my_selection"));assertThat(sources.write(4,true)).containsEntry("kind","selection");verify(pilot).requirePartner(4);verify(access).engineer();}
 @Test void supplierQuoteRetainsTheCostViewGate(){source("supplier_quote",Map.of("status","draft"));sources.write(4,true);verify(access).view("supplier_quote");verify(store).load(4,"supplier_quote",true);}
 @Test void catalogSourceHistoryIsReadableButCannotImportOrUpload(){source("selection",Map.of("catalogSource",true,"status","draft"));assertThat(sources.read(4,false)).containsEntry("catalogSource",true);assertThatThrownBy(()->sources.write(4,true)).hasMessageContaining("目录");}
 @Test void confirmedQuoteMayBeReadButCannotBeWritten(){source("supplier_quote",Map.of("status","confirmed"));assertThat(sources.read(4,false)).containsEntry("status","confirmed");assertThatThrownBy(()->sources.write(4,true)).hasMessageContaining("冻结");}
 @Test void productCannotBeUsedAsAFileTaskSource(){source("product",Map.of("status","draft"));assertThatThrownBy(()->sources.read(4,false)).hasMessageContaining("文件任务");verify(store,never()).load(anyLong(),anyString(),anyBoolean());}
 @Test void objectAccessFailureHappensBeforeKindLookup(){doThrow(new IllegalStateException("scope denied")).when(pilot).requirePartner(4);assertThatThrownBy(()->sources.read(4,false)).hasMessage("scope denied");verifyNoInteractions(jdbc);}
 @Test void bothControllerRootsUseTheIdenticalDurableHandlers(){assertThat(PartnerSelectionImportController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/v1/partner-selections","/api/v1/partner-catalog/sources");}
 @Test void ordinaryBoundImageReadKeepsExistingFilePolicyAndDoesNotRequireSupplierCosts(){var boundary=mock(PartnerFileSourceAccess.class);var material=new PartnerMaterialService(store,mock(com.yuewei.plm.module.attachment.service.DocumentService.class),mock(com.yuewei.plm.module.attachment.service.AttachmentVersionService.class),boundary);when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(44L))).thenReturn(true);material.checkReadIfBound(44);verify(pilot).requireFile(44);verifyNoInteractions(boundary);}
}
