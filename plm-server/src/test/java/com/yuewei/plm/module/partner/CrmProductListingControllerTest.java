package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.integration.crm.productlisting.*;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmProductListingControllerTest {
    @Test void malformedRequestsFailValidationBeforeReachingService() throws Exception {
        var service=mock(PartnerCrmListingApplicationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PartnerCrmListingController(service)).build();
        for(String body:List.of("{}","{\"requestId\":\"valid-request\",\"items\":[null]}",
            "{\"requestId\":\"valid-request\",\"items\":[{\"listingId\":88,\"snapshotVersion\":\""+"a".repeat(64)+"\",\"internalSkuIds\":[null]}]}")) {
            mvc.perform(post("/api/v1/partner-operations/catalog/listings/crm-pushes").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void selectedIdsAndVersionAreForwardedWithoutClientMasterData() throws Exception {
        var service=mock(PartnerCrmListingApplicationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new PartnerCrmListingController(service)).build();
        mvc.perform(post("/api/v1/partner-operations/catalog/listings/crm-pushes").contentType("application/json")
            .content("{\"requestId\":\"valid-request\",\"items\":[{\"listingId\":88,\"snapshotVersion\":\""+"a".repeat(64)+"\",\"internalSkuIds\":[101]}]}"))
            .andExpect(status().isOk());
        verify(service).submit("valid-request",List.of(Map.of("listingId",88L,"snapshotVersion","a".repeat(64),"internalSkuIds",List.of(101L))));
    }
    @Test void requestCapsAreEnforcedIncludingNestedSkuLimit() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var item=new PartnerCrmListingController.Item(88,"a".repeat(64),Collections.nCopies(101,101L));
            assertThat(validator.validate(new PartnerCrmListingController.Push("valid-request",List.of(item)))).isNotEmpty();
            assertThat(validator.validate(new PartnerCrmListingController.Push("valid-request",Collections.nCopies(21,item)))).isNotEmpty();
        }
    }
}
