package com.yuewei.plm.module.auth.service;

import com.yuewei.plm.common.security.CurrentUser;
import java.time.Instant;
import java.util.Optional;

public interface TokenSessionService {

    record Session(CurrentUser user, Instant expiresAt) {}

    String createSession(CurrentUser currentUser);

    Optional<CurrentUser> getCurrentUser(String token);

    Optional<Session> getSession(String token, boolean refreshIdleTimeout);

    default Optional<Session> getSession(String token) {
        return getSession(token, true);
    }

    void invalidate(String token);

    long getExpiresInSeconds();
}
