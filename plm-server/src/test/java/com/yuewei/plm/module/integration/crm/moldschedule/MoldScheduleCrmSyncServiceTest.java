package com.yuewei.plm.module.integration.crm.moldschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import com.yuewei.plm.module.integration.crm.moldschedule.service.MoldScheduleCrmSyncService;
import com.yuewei.plm.module.integration.crm.moldschedule.service.MoldScheduleSnapshotFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MoldScheduleCrmSyncServiceTest {
    @Test
    void ignoresOnlyVolatileDatesButQueuesChangedBusinessSnapshots() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(contains("plm_collaboration_mold_set"), eq(Long.class), any(Object[].class))).thenReturn(List.of(42L));

        AtomicReference<List<Map<String, Object>>> storedState = new AtomicReference<>(List.of());
        when(jdbc.queryForList(contains("plm_crm_mold_schedule_state"), any(Object[].class)))
            .thenAnswer(invocation -> storedState.get());

        MoldScheduleSnapshotFactory snapshots = mock(MoldScheduleSnapshotFactory.class);
        when(snapshots.create(42L)).thenReturn(snapshot("2026-11-06T10:00:00+08:00", "2026-11-06", "pending"),
            snapshot("2026-11-07T10:00:00+08:00", "2026-11-07", "pending"),
            snapshot("2026-11-07T10:00:01+08:00", "2026-11-07", "making"));
        CrmMoldScheduleProperties properties = new CrmMoldScheduleProperties();
        properties.setEnabled(true);
        MoldScheduleCrmSyncService sync = new MoldScheduleCrmSyncService(jdbc, new ObjectMapper(), snapshots, properties, transactions);

        assertThat(sync.enqueueCurrent(42L, "test", "initial")).isTrue();
        ArgumentCaptor<Object[]> stateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into plm_crm_mold_schedule_state"), stateArgs.capture());
        String firstHash = stateArgs.getValue()[2].toString();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("record_version", 1L);
        state.put("snapshot_hash", firstHash);
        state.put("deleted", false);
        state.put("delete_reason", null);
        storedState.set(List.of(state));

        assertThat(sync.enqueueCurrent(42L, "test")).isFalse();
        assertThat(sync.enqueueCurrent(42L, "test")).isTrue();

        ArgumentCaptor<Object[]> eventArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(contains("insert into plm_crm_mold_schedule_outbox"), eventArgs.capture());
        String latestEvent = eventArgs.getAllValues().get(1)[6].toString();
        assertThat(new ObjectMapper().readTree(latestEvent).path("recordVersion").asText()).isEqualTo("2");
        assertThat(new ObjectMapper().readTree(latestEvent).path("data").path("status").path("asOfDate").asText()).isEqualTo("2026-11-07");
    }

    private static Map<String, Object> snapshot(String updatedAt, String asOfDate, String statusCode) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("scheduleStatusCode", statusCode);
        status.put("asOfDate", asOfDate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moldSetId", "42");
        data.put("updatedAt", updatedAt);
        data.put("status", status);
        data.put("scheduleNotes", "unchanged");
        return data;
    }
}
