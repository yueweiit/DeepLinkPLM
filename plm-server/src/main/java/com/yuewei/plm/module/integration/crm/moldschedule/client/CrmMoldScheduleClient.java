package com.yuewei.plm.module.integration.crm.moldschedule.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.integration.crm.moldschedule.config.CrmMoldScheduleProperties;
import com.yuewei.plm.module.integration.crm.moldschedule.service.CrmHmacSigner;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class CrmMoldScheduleClient {
    private final CrmMoldScheduleProperties properties;
    private final CrmHmacSigner signer;
    private final ObjectMapper json;

    public CrmMoldScheduleClient(CrmMoldScheduleProperties properties, CrmHmacSigner signer, ObjectMapper json) {
        this.properties = properties;
        this.signer = signer;
        this.json = json;
    }

    public Receipt send(String eventId, String recordId, String recordVersion, String body) {
        if (!properties.isConfigured()) throw new CrmMoldScheduleDeliveryException("CRM 接口配置不完整或不是 HTTPS", false, null, null);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signer.signature("POST", properties.getPath(), properties.getKeyId(), timestamp, eventId, bytes, properties.getSecret());
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs())).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            var response = client.post().uri(properties.targetUrl()).contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .header("X-PLM-Key-Id", properties.getKeyId())
                .header("X-PLM-Timestamp", timestamp)
                .header("X-PLM-Event-Id", eventId)
                .header("X-PLM-Signature", signature)
                .body(body).retrieve().toEntity(String.class);
            if (response.getStatusCode().value() != 200) {
                throw new CrmMoldScheduleDeliveryException("CRM 返回非成功 HTTP 状态", false, response.getStatusCode().value(), null);
            }
            return validateAck(response.getBody(), eventId, recordId, recordVersion);
        } catch (CrmMoldScheduleDeliveryException e) {
            throw e;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status == 429 || status >= 500;
            String message = responseMessage(e.getResponseBodyAsString());
            throw new CrmMoldScheduleDeliveryException(message, retryable, status, retryAfter(e.getResponseHeaders()));
        } catch (ResourceAccessException e) {
            throw new CrmMoldScheduleDeliveryException("CRM 网络请求失败或超时", true, null, null);
        } catch (Exception e) {
            throw new CrmMoldScheduleDeliveryException("CRM 回执无法解析", true, null, null);
        }
    }

    private Receipt validateAck(String body, String eventId, String recordId, String recordVersion) {
        try {
            JsonNode ack = json.readTree(body == null ? "" : body);
            String code = text(ack, "code");
            String ackEvent = text(ack, "eventId");
            String ackRecord = text(ack, "recordId");
            String requestVersion = text(ack, "requestVersion");
            String currentVersion = text(ack, "currentVersion");
            String result = text(ack, "result");
            String receivedAt = text(ack, "receivedAt");
            boolean validResult = Set.of("applied", "duplicate", "ignored_stale").contains(result);
            boolean validVersions = validVersion(recordVersion) && validVersion(requestVersion) && validVersion(currentVersion);
            if (!"SUCCESS".equals(code) || !eventId.equals(ackEvent) || !recordId.equals(ackRecord)
                || !recordVersion.equals(requestVersion) || !validResult || receivedAt == null || !validVersions
                || new BigInteger(currentVersion).compareTo(new BigInteger(recordVersion)) < 0) {
                throw new CrmMoldScheduleDeliveryException("CRM 回执与请求不匹配", true, 200, null);
            }
            OffsetDateTime.parse(receivedAt);
            return new Receipt(200, result);
        } catch (CrmMoldScheduleDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new CrmMoldScheduleDeliveryException("CRM 回执缺少必需字段或格式无效", true, 200, null);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static boolean validVersion(String value) {
        return value != null && value.matches("[1-9][0-9]*");
    }

    private static String responseMessage(String body) {
        try {
            JsonNode json = new ObjectMapper().readTree(body);
            String code = text(json, "code");
            String message = text(json, "message");
            return (code == null ? "CRM HTTP 请求失败" : "CRM " + code) + (message == null ? "" : ": " + message.substring(0, Math.min(message.length(), 500)));
        } catch (Exception ignored) {
            return "CRM HTTP 请求失败";
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        if (headers == null || headers.getFirst(HttpHeaders.RETRY_AFTER) == null) return null;
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER).trim();
        try { return Duration.ofSeconds(Math.max(0, Long.parseLong(value))); }
        catch (NumberFormatException ignored) {
            try { return Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).isNegative()
                ? Duration.ZERO : Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); }
            catch (Exception invalid) { return null; }
        }
    }

    public record Receipt(int httpStatus, String result) {}
}
