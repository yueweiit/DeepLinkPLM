package com.yuewei.plm.module.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.config.JacksonConfig;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.common.security.SimpleTokenAuthenticationFilter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Real isolated Redis, supplied explicitly by the local validation runner; no production session import. */
@EnabledIfSystemProperty(named = "plm.auth.redis.test-port", matches = "\\d+")
class RedisTokenSessionServiceLocalIntegrationTest {
    private final ObjectMapper json = new JacksonConfig().objectMapper();
    private final CurrentUser actor = new CurrentUser(11L, "redis-local-test", "隔离会话测试", false);
    private final List<LettuceConnectionFactory> connections = new ArrayList<>();
    private StringRedisTemplate redis;
    private RedisTokenSessionService service;

    @BeforeEach
    void setup() {
        redis = connect(Integer.parseInt(System.getProperty("plm.auth.redis.test-port")));
        service = new RedisTokenSessionService(redis, json);
    }

    @AfterEach
    void cleanup() {
        var keys = redis.keys("plm:auth:session:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        connections.forEach(LettuceConnectionFactory::destroy);
        CurrentUserContext.clear();
    }

    @Test
    void normalSessionUsesOpaqueRandomTokenAndOriginalFixedEightHourExpiry() {
        String token = service.createSession(actor);
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(service.getCurrentUser(token)).contains(actor);
        assertThat(service.getExpiresInSeconds()).isEqualTo(28800L);
        assertThat(redis.getExpire(onlyKey(), TimeUnit.SECONDS)).isBetween(28795L, 28800L);
    }

    @Test
    void anotherServiceAfterConnectionShutdownRetainsSessionWithoutRenewingItsRemainingTtl() {
        String token = service.createSession(actor);
        String key = onlyKey();
        redis.expire(key, Duration.ofSeconds(120));
        connections.remove(0).destroy();
        redis = connect(Integer.parseInt(System.getProperty("plm.auth.redis.test-port")));
        var restarted = new RedisTokenSessionService(redis, json);
        assertThat(restarted.getCurrentUser(token)).contains(actor);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(115L, 120L);
        assertThat(restarted.getCurrentUser(token)).contains(actor);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(115L, 120L);
    }

    @Test
    void logoutRevokesTheSessionForOtherInstances() {
        String token = service.createSession(actor);
        var other = new RedisTokenSessionService(connect(Integer.parseInt(System.getProperty("plm.auth.redis.test-port"))), json);
        assertThat(other.getCurrentUser(token)).contains(actor);
        service.invalidate(token);
        assertThat(other.getCurrentUser(token)).isEmpty();
    }

    @Test
    void blankUnknownAndExpiredTokensRemainUnauthorized() {
        assertThat(service.getCurrentUser(null)).isEmpty();
        assertThat(service.getCurrentUser(" ")).isEmpty();
        assertThat(service.getCurrentUser("not-a-session")).isEmpty();
        service.invalidate(null);
        String token = service.createSession(actor);
        redis.expireAt(onlyKey(), Instant.EPOCH);
        assertThat(service.getCurrentUser(token)).isEmpty();
    }

    @Test
    void redisKeyDoesNotExposeTheBearerAndValueContainsOnlyExistingCurrentUserFields() throws Exception {
        String token = service.createSession(actor);
        assertThat(onlyKey()).matches("plm:auth:session:[0-9a-f]{64}").doesNotContain(token);
        var value = json.readTree(redis.opsForValue().get(onlyKey()));
        assertThat(value.fieldNames()).toIterable().containsExactlyInAnyOrder("userId", "username", "displayName", "allPermissions");
        assertThat(value.toString()).doesNotContain(token, "password", "passwordHash");
    }

    @Test
    void unavailableRedisIsAServiceFailureRatherThanAnInvalidSession() throws Exception {
        int unavailable;
        try (var socket = new ServerSocket(0)) { unavailable = socket.getLocalPort(); }
        var disconnected = new RedisTokenSessionService(connect(unavailable), json);
        assertThatThrownBy(() -> disconnected.getCurrentUser("isolated-outage-token")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> disconnected.createSession(actor)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void malformedStoredSessionIsNotSilentlyReportedAsExpired() {
        String token = service.createSession(actor);
        redis.opsForValue().set(onlyKey(), "not-json", Duration.ofSeconds(120));
        assertThatThrownBy(() -> service.getCurrentUser(token)).isInstanceOf(IllegalStateException.class).hasMessage("无法读取登录会话");
    }

    @Test
    void existingBearerFilterAcceptsPersistentSessionAndStillReturnsReal401AfterExpiry() throws Exception {
        String token = service.createSession(actor);
        var properties = new AppProperties();
        properties.getSecurity().setEnabled(true);
        var filter = new SimpleTokenAuthenticationFilter(properties, new RedisTokenSessionService(connect(Integer.parseInt(System.getProperty("plm.auth.redis.test-port"))), json), json);
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/profile");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> assertThat(CurrentUserContext.get()).contains(actor));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(CurrentUserContext.get()).isEmpty();
        redis.expireAt(onlyKey(), Instant.EPOCH);
        var expired = new MockHttpServletResponse();
        filter.doFilter(request, expired, (r, s) -> { throw new AssertionError("expired session must not reach business handler"); });
        assertThat(expired.getStatus()).isEqualTo(401);
        assertThat(expired.getContentAsString()).contains("40101");
    }

    private String onlyKey() {
        var keys = redis.keys("plm:auth:session:*");
        assertThat(keys).hasSize(1);
        return keys.iterator().next();
    }

    private StringRedisTemplate connect(int port) {
        var options = ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).build();
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).clientOptions(options).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), client);
        factory.afterPropertiesSet();
        connections.add(factory);
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
