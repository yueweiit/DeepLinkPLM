package com.yuewei.plm.module.integration.crm.moldschedule.controller;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import com.yuewei.plm.module.integration.crm.moldschedule.service.MoldScheduleCrmSyncService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/integrations/crm/mold-schedules")
public class MoldScheduleCrmSyncAdminController {
    private final PilotAccessService access;
    private final JdbcTemplate jdbc;
    private final MoldScheduleCrmSyncService sync;
    private final CrmMoldScheduleProperties properties;

    @GetMapping("/status")
    public ResponseVO<?> status(HttpServletRequest request) {
        access.requireDomesticView();
        access.requireAdministrator();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("configurationReady", properties.isConfigured());
        result.put("counts", jdbc.queryForMap("select count(*) filter(where status='pending') pending,count(*) filter(where status='sending') sending,count(*) filter(where status='retrying') retrying,count(*) filter(where status='succeeded') succeeded,count(*) filter(where status='failed') failed from plm_crm_mold_schedule_outbox"));
        result.put("latestEvents", jdbc.queryForList("select event_id,mold_set_id,record_version,event_type,status,attempt_count,next_attempt_at,last_http_status,last_error,created_at,sent_at from plm_crm_mold_schedule_outbox order by created_at desc limit 20"));
        return ResponseVO.success(result, RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PostMapping("/full-sync")
    public ResponseVO<?> fullSync(HttpServletRequest request) {
        access.requireDomesticView();
        access.requireAdministrator();
        int queued = sync.enqueueAllActive("admin-full-sync", "initial");
        return ResponseVO.success(Map.of("queued", queued), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PostMapping("/retry-failed")
    public ResponseVO<?> retryFailed(HttpServletRequest request) {
        access.requireDomesticView();
        access.requireAdministrator();
        int queued = sync.retryFailed(null);
        return ResponseVO.success(Map.of("retried", queued), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PostMapping("/{moldSetId}/retry-failed")
    public ResponseVO<?> retryRecord(@PathVariable long moldSetId, HttpServletRequest request) {
        access.requireDomesticView();
        access.requireAdministrator();
        int queued = sync.retryFailed(moldSetId);
        return ResponseVO.success(Map.of("retried", queued), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }
}
