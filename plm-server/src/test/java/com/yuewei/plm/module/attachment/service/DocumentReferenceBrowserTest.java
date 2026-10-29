package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentReferenceBrowserTest {
    final DocumentService documents=mock(DocumentService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final DocumentReferenceBrowser service=new DocumentReferenceBrowser(documents,jdbc,access,new ObjectMapper());
    @Test void missingReadPermissionFailsBeforeLoadingAnyNamesOrFacets() {
        doThrow(new BusinessException(40103,"无图纸权限")).when(access).requirePermission("file:read");
        assertThatThrownBy(()->service.page(new DocumentReferenceBrowser.Query())).hasMessageContaining("权限");
        verifyNoInteractions(documents,jdbc);
    }
    @Test void invalidTypePaginationAndIdsCannotReachTheCatalogue() {
        var q=new DocumentReferenceBrowser.Query();q.setType("unknown");
        assertThatThrownBy(()->service.page(q)).hasMessageContaining("类型");
        q.setType("product");q.setSize(10001);assertThatThrownBy(()->service.page(q)).hasMessageContaining("范围");
        q.setSize(50);q.setBrandId(-1L);assertThatThrownBy(()->service.page(q)).hasMessageContaining("ID");
        verifyNoInteractions(documents,jdbc);
    }
}
