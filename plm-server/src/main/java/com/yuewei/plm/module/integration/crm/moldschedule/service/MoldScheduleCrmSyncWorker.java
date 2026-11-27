package com.yuewei.plm.module.integration.crm.moldschedule.service;

import com.yuewei.plm.module.integration.crm.moldschedule.client.CrmMoldScheduleClient;
import com.yuewei.plm.module.integration.crm.moldschedule.client.CrmMoldScheduleDeliveryException;
import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class MoldScheduleCrmSyncWorker {
    private static final List<Duration> RETRY_DELAYS = List.of(
        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5),
        Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3));

    private final JdbcTemplate jdbc;
    private final CrmMoldScheduleClient client;
    private final CrmMoldScheduleProperties properties;
    private final TransactionTemplate transaction;

    public MoldScheduleCrmSyncWorker(JdbcTemplate jdbc, CrmMoldScheduleClient client,
                                     CrmMoldScheduleProperties properties,
                                     PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.client = client;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${plm.integrations.crm.mold-schedule.poll-interval-ms:5000}")
    public void deliverNext() {
        if (!properties.isEnabled() || !properties.isConfigured()) return;
        Claim claim = transaction.execute(status -> claim());
        if (claim == null) return;
        try {
            client.send(claim.eventId.toString(), claim.recordId, claim.recordVersion, claim.payload);
            transaction.executeWithoutResult(status -> jdbc.update("""
                update plm_crm_mold_schedule_outbox set status='succeeded',sent_at=now(),locked_until=null,
                  lease_token=null,last_error=null,last_http_status=200
                 where event_id=? and lease_token=? and status='sending'
                """, claim.eventId, claim.leaseToken));
        } catch (CrmMoldScheduleDeliveryException failure) {
            recordFailure(claim, failure.isRetryable(), failure.getHttpStatus(), failure.getRetryAfter(), failure.getMessage());
        } catch (Exception failure) {
            recordFailure(claim, true, null, null, "CRM 投递异常");
        }
    }

    private Claim claim() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select event_id,mold_set_id,record_version,payload_body,attempt_count from plm_crm_mold_schedule_outbox
             where ((status in ('pending','retrying') and next_attempt_at<=now())
                or (status='sending' and (locked_until is null or locked_until<now())))
             order by next_attempt_at,created_at,event_id limit 1 for update skip locked
            """);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        UUID eventId = (UUID) row.get("event_id");
        UUID leaseToken = UUID.randomUUID();
        int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
        jdbc.update("""
            update plm_crm_mold_schedule_outbox set status='sending',attempt_count=?,lease_token=?,
              locked_until=now()+interval '2 minutes',last_error=null where event_id=?
            """, attempt, leaseToken, eventId);
        return new Claim(eventId, leaseToken, String.valueOf(row.get("mold_set_id")),
            String.valueOf(row.get("record_version")), row.get("payload_body").toString(), attempt);
    }

    private void recordFailure(Claim claim, boolean retryable, Integer httpStatus, Duration retryAfter, String message) {
        transaction.executeWithoutResult(status -> {
            boolean retry = retryable && claim.attempt <= RETRY_DELAYS.size();
            Duration delay = retry ? jitter(RETRY_DELAYS.get(claim.attempt - 1)) : null;
            if (retryAfter != null && (delay == null || retryAfter.compareTo(delay) > 0)) delay = retryAfter;
            if (retry) {
                jdbc.update("""
                    update plm_crm_mold_schedule_outbox set status='retrying',next_attempt_at=now()+(? * interval '1 millisecond'),
                      locked_until=null,lease_token=null,last_http_status=?,last_error=?
                     where event_id=? and lease_token=? and status='sending'
                    """, delay.toMillis(), httpStatus, summary(message), claim.eventId, claim.leaseToken);
            } else {
                jdbc.update("""
                    update plm_crm_mold_schedule_outbox set status='failed',locked_until=null,lease_token=null,
                      last_http_status=?,last_error=? where event_id=? and lease_token=? and status='sending'
                    """, httpStatus, summary(message), claim.eventId, claim.leaseToken);
            }
            if (httpStatus == null || httpStatus >= 500) log.warn("CRM mold schedule delivery failed; eventId={}, status={}, retry={}", claim.eventId, httpStatus, retry);
            else log.info("CRM mold schedule delivery rejected; eventId={}, status={}, retry={}", claim.eventId, httpStatus, retry);
        });
    }

    private static Duration jitter(Duration delay) {
        long extra = ThreadLocalRandom.current().nextLong(Math.max(1, delay.toMillis() / 5 + 1));
        return delay.plusMillis(extra);
    }

    private static String summary(String message) {
        String value = message == null || message.isBlank() ? "CRM 投递失败" : message.replaceAll("[\\r\\n\\t]", " ").trim();
        return value.substring(0, Math.min(value.length(), 2000));
    }

    private record Claim(UUID eventId, UUID leaseToken, String recordId, String recordVersion, String payload, int attempt) {}
}
