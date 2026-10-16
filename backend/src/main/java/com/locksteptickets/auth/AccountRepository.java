package com.locksteptickets.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Account> findByEmail(String email) {
        return jdbcClient.sql("""
                SELECT id, email, password_hash, role, active
                FROM account
                WHERE email = :email
                """)
            .param("email", normalizeEmail(email))
            .query((resultSet, rowNumber) -> new Account(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                Account.Role.valueOf(resultSet.getString("role")),
                resultSet.getBoolean("active")))
            .optional();
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
