package com.locksteptickets.auth;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccessSessionRepository {

    private final JdbcClient jdbcClient;

    public AccessSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void create(byte[] tokenHash, UUID accountId, Instant createdAt, Instant expiresAt) {
        jdbcClient.sql("""
                INSERT INTO access_session (token_hash, account_id, created_at, expires_at)
                VALUES (:tokenHash, :accountId, :createdAt, :expiresAt)
                """)
            .param("tokenHash", tokenHash)
            .param("accountId", accountId)
            .param("createdAt", Timestamp.from(createdAt))
            .param("expiresAt", Timestamp.from(expiresAt))
            .update();
    }

    public Optional<SessionAccount> findActive(byte[] tokenHash, Instant now) {
        return jdbcClient.sql("""
                SELECT a.id, a.email, a.role
                FROM access_session s
                JOIN account a ON a.id = s.account_id
                WHERE s.token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > :now
                  AND a.active = true
                """)
            .param("tokenHash", tokenHash)
            .param("now", Timestamp.from(now))
            .query((resultSet, rowNumber) -> new SessionAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                Account.Role.valueOf(resultSet.getString("role"))))
            .optional();
    }

    public void revoke(byte[] tokenHash, Instant now) {
        jdbcClient.sql("""
                UPDATE access_session
                SET revoked_at = :now
                WHERE token_hash = :tokenHash AND revoked_at IS NULL
                """)
            .param("tokenHash", tokenHash)
            .param("now", Timestamp.from(now))
            .update();
    }

    public record SessionAccount(UUID accountId, String email, Account.Role role) {
    }
}
