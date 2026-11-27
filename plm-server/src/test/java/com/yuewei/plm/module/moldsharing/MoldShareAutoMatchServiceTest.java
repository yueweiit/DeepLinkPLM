package com.yuewei.plm.module.moldsharing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MoldShareAutoMatchServiceTest {
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PilotAccessService access = org.mockito.Mockito.mock(PilotAccessService.class);
    private final ExistingMoldGroupMappingService existing = org.mockito.Mockito.mock(ExistingMoldGroupMappingService.class);
    private final MoldShareAutoMatchService service = new MoldShareAutoMatchService(jdbc, access, existing);

    @Test void rejectsUnauthorizedConfirmationBeforeReadingMoldCandidates() {
        var input = new MoldShareAutoMatchService.Input(1, 2, 3, "precision", List.of("10"),
            null, "preview-token", "engineering evidence", "confirmed reuse");
        when(access.requireEngineeringMaintenance()).thenThrow(new BusinessException(40103, "仅工程职责或管理员可维护"));

        assertThrows(BusinessException.class, () -> service.confirm(input, null));

        verify(access).requireEngineeringMaintenance();
        verifyNoInteractions(jdbc, existing);
    }
}
