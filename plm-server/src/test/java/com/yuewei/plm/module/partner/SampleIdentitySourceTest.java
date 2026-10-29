package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SampleIdentitySourceTest {
 @Test void catalogueTypeCandidatesNeedNoInventedReceiptDatePositionOrKeeper(){
  var jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"qa","工程",false));var store=new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class));var samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));
  var catalogue=mock(PartnerCatalogStyles.class);var supplier=mock(SupplierProfileService.class);ReflectionTestUtils.setField(samples,"catalogStyles",catalogue);ReflectionTestUtils.setField(samples,"suppliers",supplier);
  var source=new LinkedHashMap<String,Object>();source.putAll(Map.of("catalogStyleId",7L,"catalogStyleCode","CP-000007","supplierCode","SUP-A","name","实际目录款","phoneModelIds",List.of(12L),"phones",List.of(Map.of("phoneModelId",12L,"displayName","Phone A")),"selectedPairs",List.of(),"receiptColorMode","at_receipt","receiptColors",List.of(Map.of("id",3L,"name","供应商黑")),"configKey","结构版A"));
  when(catalogue.receiptSnapshot(5L,"source-row",false)).thenReturn(source);when(supplier.requireSupplier("SUP-A")).thenReturn(Map.of("status","active","needsReview",false));
  var result=samples.identitySource(Map.of("sourceType","catalog","selectionId",5L,"selectionRowId","source-row","phoneModelId",12L,"colorId",3L));assertThat(result).containsEntry("catalogStyleId",7L).containsEntry("phoneModelId",12L).containsEntry("colorId",3L);assertThat(result).doesNotContainKeys("custodian","receivedQty","receivedDate","location");verifyNoInteractions(jdbc);
 }
}
