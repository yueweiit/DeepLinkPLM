package com.yuewei.plm.module.auth.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RedisTokenSessionService implements TokenSessionService {

    private static final long EXPIRES_IN_SECONDS = 8 * 60 * 60;
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
        try {
            redis.opsForValue().set(key(token), json.writeValueAsString(currentUser), Duration.ofSeconds(EXPIRES_IN_SECONDS));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存登录会话", error);
        }
        return token;
    }

    @Override
    public Optional<CurrentUser> getCurrentUser(String token) {
        if (!StringUtils.hasText(token)) return Optional.empty();
        String value = redis.opsForValue().get(key(token));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(value, CurrentUser.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取登录会话", error);
        }
    }

    @Override
    public void invalidate(String token) {
        if (StringUtils.hasText(token)) redis.delete(key(token));
    }

    @Override
    public long getExpiresInSeconds() {
        return EXPIRES_IN_SECONDS;
    }

    private static String key(String token) {
        try {
            return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("登录会话摘要不可用", error);
        }
    }
}
