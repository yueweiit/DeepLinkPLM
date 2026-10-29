package com.yuewei.plm.module.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.yuewei.plm.module.code.dto.PhoneModelReviewQueryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PhoneModelReviewServiceTest {
    @Test
    void reviewUsesNormalizedNamesAndDoesNotMutateSourceRecords() {
        CapturingJdbc jdbc = new CapturingJdbc();
        var access=mock(PilotAccessService.class);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        PhoneModelReviewService service = new PhoneModelReviewService(jdbc,new ObjectMapper(),access,mock(PhoneModelReferenceService.class),mock(OperationLogService.class));
        PhoneModelReviewQueryDTO query = new PhoneModelReviewQueryDTO();
        service.page(query);
        assertThat(jdbc.sql).contains("lower(btrim(p.model))", "group by code_value having count(distinct normalized_name)>1",
            "order by key desc limit ? offset ?").doesNotContain("update ", "insert ", "delete ");
        query.setKind("name_many_codes"); query.setPage(2); query.setKeyword("iPhone");
        service.page(query);
        assertThat(jdbc.sql).contains("group by normalized_name having count(distinct code_value)>1", "key ilike ?","PHONE_MODEL_REVIEW","source_digest","all_references_visible");
        assertThat(jdbc.args).containsExactly("name_many_codes","%iPhone%", "%iPhone%", "%iPhone%", 50L, 50L);
        query.setReviewState("confirmed");service.page(query);assertThat(jdbc.sql).contains("review_state=?");
    }
    private static class CapturingJdbc extends JdbcTemplate {
        String sql; Object[] args;
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) { return type.cast(55L); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            this.sql = sql; this.args = args; return List.of();
        }
    }
}
