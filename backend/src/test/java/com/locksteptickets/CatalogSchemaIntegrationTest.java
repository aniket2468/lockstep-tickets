package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class CatalogSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void databaseRejectsPlaintextPasswordsAndInvalidRoles() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role)
                VALUES ('plain@example.com', 'not-a-hash', 'CUSTOMER')
                """).update())
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role)
                VALUES ('role@example.com', '$2a$12$01234567890123456789012345678901234567890123456789012', 'OWNER')
                """).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeMoneyAndInvalidEventOrdering() {
        jdbcClient.sql("""
                INSERT INTO venue (id, name, time_zone, address)
                VALUES ('10000000-0000-0000-0000-000000000099', 'Test', 'UTC', 'Test')
                """).update();

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO event (
                    venue_id, name, sales_start_at, sales_end_at, starts_at,
                    time_zone, currency, service_fee_minor
                ) VALUES (
                    '10000000-0000-0000-0000-000000000099', 'Invalid',
                    '2030-01-02T00:00:00Z', '2030-01-01T00:00:00Z',
                    '2030-01-03T00:00:00Z', 'UTC', 'USD', -1
                )
                """).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void referencedCatalogRowsCannotCascadeAway() {
        jdbcClient.sql("""
                INSERT INTO venue (id, name, time_zone, address)
                VALUES ('10000000-0000-0000-0000-000000000098', 'Test', 'UTC', 'Test')
                """).update();
        jdbcClient.sql("""
                INSERT INTO section (id, venue_id, name, display_order)
                VALUES (
                    '20000000-0000-0000-0000-000000000098',
                    '10000000-0000-0000-0000-000000000098', 'Main', 0
                )
                """).update();

        assertThatThrownBy(() -> jdbcClient.sql("""
                DELETE FROM venue WHERE id = '10000000-0000-0000-0000-000000000098'
                """).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
