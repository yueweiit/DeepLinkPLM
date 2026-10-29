package com.yuewei.plm.module.collaboration;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoldMasterRepositoryTest {
    @Test void aggregatePageUsesSameTwoDatabaseQueriesForOneOrFiftyArchives() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var repository=mock(CollaborationRepository.class);var master=new MoldMasterRepository(jdbc,repository);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(50L);
        var q=new CollaborationTypes.Query();q.setView("master");q.setSize(1);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(rows(1));
        assertThat(master.page(q,"true","true").getContent()).hasSize(1);
        verify(jdbc,times(1)).queryForObject(anyString(),eq(Long.class),any(Object[].class));verify(jdbc,times(1)).query(anyString(),any(RowMapper.class),any(Object[].class));verifyNoMoreInteractions(jdbc);
        clearInvocations(jdbc);q.setSize(50);when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(rows(50));
        assertThat(master.page(q,"true","true").getContent()).hasSize(50);
        verify(jdbc,times(1)).queryForObject(anyString(),eq(Long.class),any(Object[].class));verify(jdbc,times(1)).query(anyString(),any(RowMapper.class),any(Object[].class));verifyNoMoreInteractions(jdbc);
    }
    private List<Map<String,Object>> rows(int count) {
        List<Map<String,Object>> rows=new ArrayList<>();for(int n=0;n<count;n++) {Map<String,Object> row=new LinkedHashMap<>();row.put("itemId",n+1);row.put("compatibleModels","原始型号");row.put("members",List.of());rows.add(row);}return rows;
    }
}
