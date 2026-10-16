package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.locksteptickets.config.DemoDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
    "lockstep.demo.customer-password=customer-test-password",
    "lockstep.demo.admin-password=admin-test-password"
})
@ActiveProfiles("demo")
@Testcontainers
class DemoProfileIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DemoDataInitializer demoDataInitializer;

    @Test
    void explicitDemoProfileCreatesHashedAccountsAndCatalogFixture() {
        assertThat(jdbcClient.sql("SELECT count(*) FROM account")
            .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM event")
            .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("SELECT count(*) FROM event_seat")
            .query(Long.class).single()).isEqualTo(36);
        assertThat(jdbcClient.sql("SELECT count(*) FROM promo_code")
            .query(Long.class).single()).isEqualTo(2);

        String customerHash = jdbcClient.sql("""
                SELECT password_hash FROM account WHERE email = 'customer@lockstep.test'
                """).query(String.class).single();
        assertThat(customerHash).doesNotContain("customer-test-password");
        assertThat(passwordEncoder.matches("customer-test-password", customerHash)).isTrue();
    }

    @Test
    @Transactional
    void refusesToOverwriteAnAccountWithCollidingDemoEmail() {
        jdbcClient.sql("DELETE FROM account WHERE email = 'customer@lockstep.test'").update();
        UUID existingId = jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role, active)
                VALUES ('customer@lockstep.test', :passwordHash, 'ADMIN', false)
                RETURNING id
                """)
            .param("passwordHash", passwordEncoder.encode("existing-account-password"))
            .query(UUID.class)
            .single();

        assertThatThrownBy(() -> demoDataInitializer.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("isolated demo database");
        assertThat(jdbcClient.sql("""
                SELECT id FROM account WHERE email = 'customer@lockstep.test'
                """).query(UUID.class).single()).isEqualTo(existingId);
    }
}
