package com.yuewei.plm.module.integration.crm.moldschedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MoldScheduleCrmSyncService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MoldScheduleSnapshotFactory snapshots;
    private final CrmMoldScheduleProperties properties;
    private final TransactionTemplate transaction;

    public MoldScheduleCrmSyncService(JdbcTemplate jdbc, ObjectMapper json, MoldScheduleSnapshotFactory snapshots,
                                     CrmMoldScheduleProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.snapshots = snapshots;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
    }

    public boolean enqueueCurrent(long moldSetId, String source) {
        return enqueueCurrent(moldSetId, source, "change");
    }

    public boolean enqueueCurrent(long moldSetId, String source, String syncMode) {
        if (!properties.isEnabled()) return false;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            List<Long> active = jdbc.queryForList("""
                select mold_set_id from plm_collaboration_mold_set
                 where mold_set_id=? and deleted_flag=0 for update
                """, Long.class, moldSetId);
            if (active.isEmpty()) return false;
            Map<String, Object> data = snapshots.create(moldSetId);
            return enqueueUpsert(moldSetId, data, source, syncMode);
        }));
    }

    public int enqueueForItem(long itemId, String source) {
        if (!properties.isEnabled()) return 0;
        List<Long> ids = jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where item_id=? and deleted_flag=0 order by mold_set_id", Long.class, itemId);
        return enqueueIds(ids, source);
    }

    public int enqueueForProductLine(long productLineId, String source) {
        if (!properties.isEnabled()) return 0;
        List<Long> ids = jdbc.queryForList("""
            select s.mold_set_id from plm_collaboration_mold_set s
             join plm_collaboration_item i on i.item_id=s.item_id
             where i.product_line_id=? and i.deleted_flag=0 and s.deleted_flag=0 order by s.mold_set_id
            """, Long.class, productLineId);
        return enqueueIds(ids, source);
    }

    public int enqueueForUser(long userId, String source) {
        if (!properties.isEnabled()) return 0;
        List<Long> ids = jdbc.queryForList("""
            select mold_set_id from plm_collaboration_mold_set where deleted_flag=0
             and (owner_user_id=? or logistics_owner_user_id=? or mexico_owner_user_id=? or production_owner_user_id=?)
             order by mold_set_id
            """, Long.class, userId, userId, userId, userId);
        return enqueueIds(ids, source);
    }

    private int enqueueIds(List<Long> ids, String source) {
        int queued = 0;
        for (Long id : ids) if (enqueueCurrent(id, source)) queued++;
        return queued;
    }

    public int enqueueAllActive(String source, String syncMode) {
        if (!properties.isEnabled()) throw new IllegalStateException("CRM mold schedule integration is disabled");
        int queued = 0;
        long after = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList("""
                select mold_set_id from plm_collaboration_mold_set
                 where deleted_flag=0 and mold_set_id>? order by mold_set_id limit 200
                """, Long.class, after);
            if (ids.isEmpty()) break;
            for (Long id : ids) if (enqueueCurrent(id, source, syncMode)) queued++;
            after = ids.get(ids.size() - 1);
        }
        return queued;
    }

    public boolean enqueueDelete(long moldSetId, String source, String deleteReason) {
        if (!properties.isEnabled()) return false;
        if (!List.of("deleted", "voided", "out_of_scope").contains(deleteReason)) throw new IllegalArgumentException("Invalid CRM delete reason");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? for update", Long.class, moldSetId);
            return enqueueDeleteLocked(moldSetId, source, deleteReason);
        }));
    }

    public int enqueueDeleteForItem(long itemId, String source, String deleteReason) {
        if (!properties.isEnabled()) return 0;
        List<Long> ids = jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where item_id=? and deleted_flag=0 order by mold_set_id", Long.class, itemId);
        int queued = 0;
        for (Long id : ids) if (enqueueDelete(id, source, deleteReason)) queued++;
        return queued;
    }

    public int retryFailed(Long moldSetId) {
        if (!properties.isEnabled()) throw new IllegalStateException("CRM mold schedule integration is disabled");
        if (moldSetId == null) return jdbc.update("""
            update plm_crm_mold_schedule_outbox set status='retrying',attempt_count=0,next_attempt_at=now(),
              lease_token=null,locked_until=null,last_error=null where status='failed'
            """);
        return jdbc.update("""
            update plm_crm_mold_schedule_outbox set status='retrying',attempt_count=0,next_attempt_at=now(),
              lease_token=null,locked_until=null,last_error=null where status='failed' and mold_set_id=?
            """, moldSetId);
    }

    private boolean enqueueUpsert(long moldSetId, Map<String, Object> data, String source, String syncMode) {
        String hash = digest(data);
        var state = stateForUpdate(moldSetId);
        if (state != null && !Boolean.TRUE.equals(state.get("deleted")) && hash.equals(state.get("snapshot_hash"))) return false;
        long version = state == null ? 1 : ((Number) state.get("record_version")).longValue() + 1;
        String eventId = UUID.randomUUID().toString();
        String occurredAt = (String) data.get("updatedAt");
        Map<String, Object> event = envelope(eventId, "moldSchedule.upsert", moldSetId, version, occurredAt, syncMode, null, data);
        insert(eventId, moldSetId, version, "moldSchedule.upsert", syncMode, source, event);
        jdbc.update("""
            insert into plm_crm_mold_schedule_state(mold_set_id,record_version,snapshot_hash,deleted,delete_reason,updated_at)
            values(?,?,?,false,null,now())
            on conflict(mold_set_id) do update set record_version=excluded.record_version,snapshot_hash=excluded.snapshot_hash,
              deleted=false,delete_reason=null,updated_at=now()
            """, moldSetId, version, hash);
        return true;
    }

    private boolean enqueueDeleteLocked(long moldSetId, String source, String deleteReason) {
        var state = stateForUpdate(moldSetId);
        if (state != null && Boolean.TRUE.equals(state.get("deleted")) && deleteReason.equals(state.get("delete_reason"))) return false;
        long version = state == null ? 1 : ((Number) state.get("record_version")).longValue() + 1;
        String eventId = UUID.randomUUID().toString();
        String occurredAt = java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        Map<String, Object> event = envelope(eventId, "moldSchedule.delete", moldSetId, version, occurredAt, "change", deleteReason, null);
        insert(eventId, moldSetId, version, "moldSchedule.delete", "change", source, event);
        jdbc.update("""
            insert into plm_crm_mold_schedule_state(mold_set_id,record_version,snapshot_hash,deleted,delete_reason,updated_at)
            values(?,?,null,true,?,now())
            on conflict(mold_set_id) do update set record_version=excluded.record_version,snapshot_hash=null,
              deleted=true,delete_reason=excluded.delete_reason,updated_at=now()
            """, moldSetId, version, deleteReason);
        return true;
    }

    private Map<String, Object> stateForUpdate(long moldSetId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select record_version,snapshot_hash,deleted,delete_reason from plm_crm_mold_schedule_state
             where mold_set_id=? for update
            """, moldSetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> envelope(String eventId, String eventType, long moldSetId, long version,
                                         String occurredAt, String syncMode, String deleteReason, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "1.0");
        event.put("eventId", eventId);
        event.put("eventType", eventType);
        event.put("sourceSystem", "PLM");
        event.put("entityType", "moldSchedule");
        event.put("recordId", String.valueOf(moldSetId));
        event.put("recordVersion", String.valueOf(version));
        event.put("occurredAt", occurredAt);
        event.put("syncMode", syncMode);
        event.put("deleteReason", deleteReason);
        event.put("data", data);
        return event;
    }

    private void insert(String eventId, long moldSetId, long version, String eventType, String syncMode,
                        String source, Map<String, Object> event) {
        try {
            jdbc.update("""
                insert into plm_crm_mold_schedule_outbox(event_id,mold_set_id,record_version,event_type,sync_mode,trigger_source,payload_body)
                values(?,?,?,?,?,?,?)
                """, UUID.fromString(eventId), moldSetId, version, eventType, syncMode,
                source == null || source.isBlank() ? "unknown" : source.substring(0, Math.min(source.length(), 64)),
                json.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist CRM mold schedule event", e);
        }
    }

    private String digest(Map<String, Object> data) {
        try {
            JsonNode normalized = json.valueToTree(data);
            if (normalized instanceof ObjectNode root) {
                root.remove("updatedAt");
                JsonNode status = root.get("status");
                if (status instanceof ObjectNode statusObject) statusObject.remove("asOfDate");
            }
            byte[] canonical = json.writeValueAsBytes(canonical(normalized));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot calculate CRM mold schedule snapshot digest", e);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonical(value)));
            return sorted;
        }
        if (node.isArray()) {
            var sorted = json.createArrayNode();
            node.forEach(value -> sorted.add(canonical(value)));
            return sorted;
        }
        return node;
    }
}
