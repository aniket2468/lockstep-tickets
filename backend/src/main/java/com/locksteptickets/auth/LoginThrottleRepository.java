package com.locksteptickets.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LoginThrottleRepository {

    private final JdbcClient jdbcClient;

    public LoginThrottleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Instant> blockedUntil(byte[] identifierHash, Instant now) {
        return jdbcClient.sql("""
                SELECT blocked_until
                FROM login_throttle
                WHERE identifier_hash = :identifierHash
                  AND blocked_until > :now
                """)
            .param("identifierHash", identifierHash)
            .param("now", Timestamp.from(now))
            .query((resultSet, rowNumber) -> resultSet.getTimestamp("blocked_until").toInstant())
            .optional();
    }

    public Optional<Instant> recordFailure(
        byte[] identifierHash,
        Instant now,
        Duration window,
        Duration blockDuration,
        int maxFailures
    ) {
        Instant windowCutoff = now.minus(window);
        Instant newBlockedUntil = now.plus(blockDuration);
        return jdbcClient.sql("""
                INSERT INTO login_throttle (
                    identifier_hash, window_started_at, failure_count, blocked_until
                ) VALUES (:identifierHash, :now, 1, NULL)
                ON CONFLICT (identifier_hash) DO UPDATE SET
                    window_started_at = CASE
                        WHEN login_throttle.window_started_at <= :windowCutoff THEN :now
                        ELSE login_throttle.window_started_at
                    END,
                    failure_count = CASE
                        WHEN login_throttle.window_started_at <= :windowCutoff THEN 1
                        ELSE login_throttle.failure_count + 1
                    END,
                    blocked_until = CASE
                        WHEN login_throttle.blocked_until > :now THEN login_throttle.blocked_until
                        WHEN login_throttle.window_started_at <= :windowCutoff THEN NULL
                        WHEN login_throttle.failure_count + 1 >= :maxFailures THEN :newBlockedUntil
                        ELSE NULL
                    END
                RETURNING blocked_until
                """)
            .param("identifierHash", identifierHash)
            .param("now", Timestamp.from(now))
            .param("windowCutoff", Timestamp.from(windowCutoff))
            .param("newBlockedUntil", Timestamp.from(newBlockedUntil))
            .param("maxFailures", maxFailures)
            .query((resultSet, rowNumber) -> {
                Timestamp value = resultSet.getTimestamp("blocked_until");
                return value == null ? null : value.toInstant();
            })
            .optional();
    }

    public void clear(byte[] identifierHash) {
        jdbcClient.sql("DELETE FROM login_throttle WHERE identifier_hash = :identifierHash")
            .param("identifierHash", identifierHash)
            .update();
    }
}
