package com.yuewei.plm.module.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RedisTokenSessionService implements TokenSessionService {

    private static final long IDLE_EXPIRES_IN_SECONDS = 24 * 60 * 60;
    private static final long MAX_LIFETIME_SECONDS = 7 * 24 * 60 * 60;
    private static final String PREFIX = "plm:auth:session:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisTokenSessionService(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public String createSession(CurrentUser currentUser) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant createdAt = Instant.now();
        try {
            redis.opsForValue().set(key(token), json.writeValueAsString(new StoredSession(currentUser, createdAt)),
                Duration.ofSeconds(IDLE_EXPIRES_IN_SECONDS));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存登录会话", error);
        }
        return token;
    }

    @Override
    public Optional<CurrentUser> getCurrentUser(String token) {
        return getSession(token).map(Session::user);
    }

    @Override
    public Optional<Session> getSession(String token) {
        return getSession(token, true);
    }

    @Override
    public Optional<Session> getSession(String token, boolean refreshIdleTimeout) {
        if (!StringUtils.hasText(token)) return Optional.empty();
        String redisKey = key(token);
        String value = redis.opsForValue().get(redisKey);
        if (value == null) return Optional.empty();
        try {
            JsonNode stored = json.readTree(value);
            if (!stored.has("user")) return legacySession(redisKey, stored);

            StoredSession session = json.treeToValue(stored, StoredSession.class);
            Instant now = Instant.now();
            Instant absoluteExpiry = session.createdAt().plusSeconds(MAX_LIFETIME_SECONDS);
            Instant expiresAt;
            if (refreshIdleTimeout) {
                expiresAt = now.plusSeconds(IDLE_EXPIRES_IN_SECONDS);
            } else {
                Long remainingMillis = redis.getExpire(redisKey, TimeUnit.MILLISECONDS);
                if (remainingMillis == null || remainingMillis <= 0) return Optional.empty();
                expiresAt = now.plusMillis(remainingMillis);
            }
            if (absoluteExpiry.isBefore(expiresAt)) expiresAt = absoluteExpiry;
            if (!expiresAt.isAfter(now)) {
                redis.delete(redisKey);
                return Optional.empty();
            }

            if (refreshIdleTimeout) {
                long ttlMillis = Duration.between(now, expiresAt).toMillis();
                if (!Boolean.TRUE.equals(redis.expire(redisKey, Duration.ofMillis(ttlMillis)))) return Optional.empty();
            }
            return Optional.of(new Session(session.user(), expiresAt));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取登录会话", error);
        }
    }

    private Optional<Session> legacySession(String redisKey, JsonNode stored) throws JsonProcessingException {
        CurrentUser user = json.treeToValue(stored, CurrentUser.class);
        Long remainingMillis = redis.getExpire(redisKey, TimeUnit.MILLISECONDS);
        if (remainingMillis == null || remainingMillis <= 0) return Optional.empty();
        return Optional.of(new Session(user, Instant.now().plusMillis(remainingMillis)));
    }

    @Override
    public void invalidate(String token) {
        if (StringUtils.hasText(token)) redis.delete(key(token));
    }

    @Override
    public long getExpiresInSeconds() {
        return IDLE_EXPIRES_IN_SECONDS;
    }

    private record StoredSession(CurrentUser user, Instant createdAt) {}

    private static String key(String token) {
        try {
            return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("登录会话摘要不可用", error);
        }
    }
}
