package com.locksteptickets.config;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {

    private static final UUID VENUE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECTION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SEAT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TIER_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_SEAT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("80000000-0000-0000-0000-000000000001");

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String customerEmail;
    private final String customerPassword;
    private final String adminEmail;
    private final String adminPassword;

    public DemoDataInitializer(
        JdbcClient jdbcClient,
        PasswordEncoder passwordEncoder,
        Clock clock,
        @Value("${lockstep.demo.customer-email}") String customerEmail,
        @Value("${lockstep.demo.customer-password}") String customerPassword,
        @Value("${lockstep.demo.admin-email}") String adminEmail,
        @Value("${lockstep.demo.admin-password}") String adminPassword
    ) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.customerEmail = customerEmail;
        this.customerPassword = customerPassword;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        requireSecurePassword("LOCKSTEP_DEMO_CUSTOMER_PASSWORD", customerPassword);
        requireSecurePassword("LOCKSTEP_DEMO_ADMIN_PASSWORD", adminPassword);
        insertDemoAccount(CUSTOMER_ID, customerEmail, customerPassword, "CUSTOMER");
        insertDemoAccount(ADMIN_ID, adminEmail, adminPassword, "ADMIN");
        seedCatalog();
    }

    private void insertDemoAccount(UUID id, String email, String password, String role) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        jdbcClient.sql("""
                INSERT INTO account (id, email, password_hash, role)
                VALUES (:id, :email, :passwordHash, :role)
                ON CONFLICT DO NOTHING
                """)
            .param("id", id)
            .param("email", normalizedEmail)
            .param("passwordHash", passwordEncoder.encode(password))
            .param("role", role)
            .update();

        DemoAccount stored = jdbcClient.sql("""
                SELECT id, email, password_hash, role, active
                FROM account
                WHERE id = :id AND email = :email
                """)
            .param("id", id)
            .param("email", normalizedEmail)
            .query((resultSet, rowNumber) -> new DemoAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                resultSet.getString("role"),
                resultSet.getBoolean("active")))
            .optional()
            .orElseThrow(() -> new IllegalStateException(
                "Demo account identity conflicts with existing data; use an isolated demo database"));
        if (!stored.active() || !stored.role().equals(role)
            || !passwordEncoder.matches(password, stored.passwordHash())) {
            throw new IllegalStateException(
                "Demo account differs from configured values; reset the isolated demo database");
        }
    }

    private void seedCatalog() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        jdbcClient.sql("""
                INSERT INTO venue (id, name, time_zone, address)
                VALUES (:id, 'Lockstep Theater', 'America/Indiana/Indianapolis', '1 Demo Way')
                ON CONFLICT (id) DO NOTHING
                """).param("id", VENUE_ID).update();
        jdbcClient.sql("""
                INSERT INTO section (id, venue_id, name, display_order)
                VALUES (:id, :venueId, 'Orchestra', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", SECTION_ID).param("venueId", VENUE_ID).update();
        jdbcClient.sql("""
                INSERT INTO seat (id, section_id, row_label, seat_number, display_order)
                VALUES (:id, :sectionId, 'A', '1', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", SEAT_ID).param("sectionId", SECTION_ID).update();
        jdbcClient.sql("""
                INSERT INTO event (
                    id, venue_id, name, state, sales_start_at, sales_end_at,
                    starts_at, time_zone, currency, service_fee_minor
                ) VALUES (
                    :id, :venueId, 'Lockstep Demo', 'ON_SALE', :salesStart,
                    :salesEnd, :startsAt, 'America/Indiana/Indianapolis', 'USD', 250
                )
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", EVENT_ID).param("venueId", VENUE_ID)
            .param("salesStart", Timestamp.from(now.minus(1, ChronoUnit.DAYS)))
            .param("salesEnd", Timestamp.from(now.plus(29, ChronoUnit.DAYS)))
            .param("startsAt", Timestamp.from(now.plus(30, ChronoUnit.DAYS))).update();
        jdbcClient.sql("""
                INSERT INTO price_tier (id, event_id, name, price_minor, currency)
                VALUES (:id, :eventId, 'Standard', 5000, 'USD')
                ON CONFLICT (id) DO NOTHING
                """).param("id", TIER_ID).param("eventId", EVENT_ID).update();
        jdbcClient.sql("""
                INSERT INTO event_seat (
                    id, event_id, source_seat_id, price_tier_id, section_name,
                    row_label, seat_number, display_order, accessible
                ) VALUES (
                    :id, :eventId, :seatId, :tierId, 'Orchestra', 'A', '1', 0, false
                )
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", EVENT_SEAT_ID).param("eventId", EVENT_ID)
            .param("seatId", SEAT_ID).param("tierId", TIER_ID).update();
    }

    private static void requireSecurePassword(String name, String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException(name + " must contain at least 12 characters in demo mode");
        }
    }

    private record DemoAccount(
        UUID id,
        String email,
        String passwordHash,
        String role,
        boolean active
    ) {
    }
}
