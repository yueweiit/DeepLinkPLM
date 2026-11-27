package com.yuewei.plm.module.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.config.JacksonConfig;
import com.yuewei.plm.common.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentCaptor;

class RedisTokenSessionServiceTest {
    private final ObjectMapper json = new JacksonConfig().objectMapper();
    private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
    private final CurrentUser actor = new CurrentUser(11L, "auth-test", "会话测试", false);
    private RedisTokenSessionService service;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        service = new RedisTokenSessionService(redis, json);
    }

    @Test
    void refreshesIdleExpiryOnAuthenticatedActivity() throws Exception {
        Instant createdAt = Instant.now();
        when(values.get(anyString())).thenReturn(json.writeValueAsString(new StoredSessionFixture(actor, createdAt)));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        var session = service.getSession("opaque-test-token").orElseThrow();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis).expire(anyString(), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofHours(23), Duration.ofHours(24));
        assertThat(session.user()).isEqualTo(actor);
        assertThat(session.expiresAt()).isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    void passiveSessionCheckReadsRemainingTtlWithoutRefreshingIt() throws Exception {
        when(values.get(anyString())).thenReturn(json.writeValueAsString(new StoredSessionFixture(actor, Instant.now())));
        when(redis.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS))).thenReturn(60_000L);

        var session = service.getSession("opaque-test-token", false).orElseThrow();

        assertThat(session.user()).isEqualTo(actor);
        assertThat(Duration.between(Instant.now(), session.expiresAt())).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(60));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void stopsRenewingAtTheSevenDayAbsoluteLimit() throws Exception {
        Instant createdAt = Instant.now().minus(Duration.ofDays(7)).plusSeconds(120);
        when(values.get(anyString())).thenReturn(json.writeValueAsString(new StoredSessionFixture(actor, createdAt)));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        var session = service.getSession("opaque-test-token").orElseThrow();

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(redis).expire(anyString(), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(115), Duration.ofSeconds(120));
        assertThat(session.expiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(120));
    }

    @Test
    void rejectsSessionsPastTheAbsoluteLimit() throws Exception {
        Instant createdAt = Instant.now().minus(Duration.ofDays(7)).minusSeconds(1);
        when(values.get(anyString())).thenReturn(json.writeValueAsString(new StoredSessionFixture(actor, createdAt)));

        assertThat(service.getSession("opaque-test-token")).isEmpty();

        verify(redis).delete(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void honorsRemainingExpiryForPreUpgradeSessionValues() throws Exception {
        when(values.get(anyString())).thenReturn(json.writeValueAsString(actor));
        when(redis.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS))).thenReturn(60_000L);

        var session = service.getSession("opaque-test-token").orElseThrow();

        assertThat(session.user()).isEqualTo(actor);
        assertThat(Duration.between(Instant.now(), session.expiresAt())).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(60));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    private record StoredSessionFixture(CurrentUser user, Instant createdAt) {}
}
