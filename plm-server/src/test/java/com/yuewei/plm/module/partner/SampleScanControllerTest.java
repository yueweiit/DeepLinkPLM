package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SampleScanControllerTest {
 @Test void domesticScanUsesTheSameFinancialRedactionAsIdentityDetails(){
  var identities=mock(SampleIdentityRegistry.class);var access=mock(PilotAccessService.class);var visibility=mock(PartnerVisibility.class);
  when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(1L,"engineer","工程",false),"domestic",List.of("partner:view"),true,true));
  var raw=Map.of("type","identity","identity",Map.of("priceCents",9999,"name","样品"));var safe=Map.of("type","identity","identity",Map.of("name","样品"));when(identities.resolve("SY-000101")).thenReturn(raw);when(visibility.redact(raw)).thenReturn(safe);
  var result=(ResponseVO<?>)new SampleScanController(identities,access,visibility).scan("SY-000101",new MockHttpServletRequest());assertThat(result.getData()).isEqualTo(safe);verify(visibility).redact(raw);
 }
 @Test void mexicoScanUsesTheSameLocalPositionProjectionAsTheWarehouse(){
  var identities=mock(SampleIdentityRegistry.class);var access=mock(PilotAccessService.class);var visibility=mock(PartnerVisibility.class);
  when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(2L,"mx","墨西哥",false),"mexico",List.of("task:arrival"),false,true));
  var local=Map.<String,Object>of("type","identity","sampleCode","SY-000101","identity",Map.of("positions",List.of(Map.of("site","MX","onhand",2))));when(identities.resolve("SY-000101")).thenReturn(local);
  var result=(ResponseVO<?>)new SampleScanController(identities,access,visibility).scan("SY-000101",new MockHttpServletRequest());assertThat(result.getData()).isEqualTo(local);verify(identities).resolve("SY-000101");verifyNoInteractions(visibility);
 }
}
