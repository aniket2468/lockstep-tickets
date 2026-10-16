package com.locksteptickets.config;

import java.nio.charset.StandardCharsets;
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
    private static final UUID BALCONY_SECTION_ID = demoId("section-balcony");
    private static final UUID CONCURRENCY_EVENT_ID = demoId("event-concurrency-live");
    private static final UUID SECURITY_EVENT_ID = demoId("event-security-summit");

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
                INSERT INTO section (id, venue_id, name, display_order)
                VALUES (:id, :venueId, 'Balcony', 1)
                ON CONFLICT (id) DO NOTHING
                """).param("id", BALCONY_SECTION_ID).param("venueId", VENUE_ID).update();

        for (int number = 1; number <= 6; number++) {
            seedSeat(number == 1 ? SEAT_ID : demoId("seat-orchestra-a-" + number),
                SECTION_ID, "A", number, number - 1, number == 6);
            seedSeat(demoId("seat-balcony-b-" + number), BALCONY_SECTION_ID,
                "B", number, number - 1, number == 6);
        }

        seedEvent(EVENT_ID, "Lockstep Demo", now, 30, 250, 5000, 5000, false);
        seedEvent(CONCURRENCY_EVENT_ID, "Concurrency Live", now, 14, 300, 7500, 4500, true);
        seedEvent(SECURITY_EVENT_ID, "Indy Security Summit", now, 60, 150, 6000, 3500, true);
        seedPromos(now);
    }

    private void seedSeat(UUID id, UUID sectionId, String row, int number, int order,
                          boolean accessible) {
        jdbcClient.sql("""
                INSERT INTO seat (id, section_id, row_label, seat_number, display_order, accessible)
                VALUES (:id, :sectionId, :row, :number, :displayOrder, :accessible)
                ON CONFLICT (id) DO NOTHING
                """).param("id", id).param("sectionId", sectionId).param("row", row)
            .param("number", Integer.toString(number)).param("displayOrder", order)
            .param("accessible", accessible).update();
    }

    private void seedEvent(UUID eventId, String name, Instant now, int daysUntilStart,
                           long serviceFee, long orchestraPrice, long balconyPrice,
                           boolean oneBlockedSeat) {
        jdbcClient.sql("""
                INSERT INTO event (
                    id, venue_id, name, state, sales_start_at, sales_end_at,
                    starts_at, time_zone, currency, service_fee_minor,
                    early_bird_ends_at, early_bird_discount_bps,
                    group_size_threshold, group_discount_bps
                ) VALUES (
                    :id, :venueId, :name, 'ON_SALE', :salesStart,
                    :salesEnd, :startsAt, 'America/Indiana/Indianapolis', 'USD', :fee,
                    :earlyEnd, 1000, 3, 500
                )
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", eventId).param("venueId", VENUE_ID).param("name", name)
            .param("fee", serviceFee)
            .param("salesStart", Timestamp.from(now.minus(1, ChronoUnit.DAYS)))
            .param("salesEnd", Timestamp.from(now.plus(daysUntilStart - 1L, ChronoUnit.DAYS)))
            .param("startsAt", Timestamp.from(now.plus(daysUntilStart, ChronoUnit.DAYS)))
            .param("earlyEnd", Timestamp.from(now.plus(5, ChronoUnit.DAYS))).update();

        UUID orchestraTier = eventId.equals(EVENT_ID) ? TIER_ID : demoId(eventId + "-orchestra-tier");
        UUID balconyTier = demoId(eventId + "-balcony-tier");
        seedTier(orchestraTier, eventId, "Orchestra", orchestraPrice);
        seedTier(balconyTier, eventId, "Balcony", balconyPrice);

        int displayOrder = 0;
        for (int number = 1; number <= 6; number++) {
            UUID source = number == 1 ? SEAT_ID : demoId("seat-orchestra-a-" + number);
            UUID eventSeat = eventId.equals(EVENT_ID) && number == 1
                ? EVENT_SEAT_ID : demoId(eventId + "-orchestra-a-" + number);
            seedEventSeat(eventSeat, eventId, source, orchestraTier, "Orchestra", "A",
                number, displayOrder++, number == 6, false);
        }
        for (int number = 1; number <= 6; number++) {
            boolean blocked = oneBlockedSeat && number == 6;
            seedEventSeat(demoId(eventId + "-balcony-b-" + number), eventId,
                demoId("seat-balcony-b-" + number), balconyTier, "Balcony", "B",
                number, displayOrder++, number == 6, blocked);
        }
    }

    private void seedTier(UUID id, UUID eventId, String name, long price) {
        jdbcClient.sql("""
                INSERT INTO price_tier (id, event_id, name, price_minor, currency)
                VALUES (:id, :eventId, :name, :price, 'USD')
                ON CONFLICT (id) DO NOTHING
                """).param("id", id).param("eventId", eventId).param("name", name)
            .param("price", price).update();
    }

    private void seedEventSeat(UUID id, UUID eventId, UUID sourceSeatId, UUID tierId,
                               String section, String row, int number, int displayOrder,
                               boolean accessible, boolean blocked) {
        jdbcClient.sql("""
                INSERT INTO event_seat (
                    id, event_id, source_seat_id, price_tier_id, section_name,
                    row_label, seat_number, display_order, accessible, inventory_state
                ) VALUES (
                    :id, :eventId, :seatId, :tierId, :section, :row, :number,
                    :displayOrder, :accessible, :inventoryState
                )
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", id).param("eventId", eventId).param("seatId", sourceSeatId)
            .param("tierId", tierId).param("section", section).param("row", row)
            .param("number", Integer.toString(number)).param("displayOrder", displayOrder)
            .param("accessible", accessible).param("inventoryState", blocked ? "BLOCKED" : "AVAILABLE")
            .update();
    }

    private void seedPromos(Instant now) {
        seedPromo(demoId("promo-save10"), null, "SAVE10", 1000, false, now);
        seedPromo(demoId("promo-vip15"), CONCURRENCY_EVENT_ID, "VIP15", 1500, true, now);
    }

    private void seedPromo(UUID id, UUID eventId, String code, long discountBps,
                           boolean exclusive, Instant now) {
        jdbcClient.sql("""
                INSERT INTO promo_code (
                    id, event_id, code, discount_type, discount_value, valid_from,
                    valid_until, global_limit, per_customer_limit, exclusive
                ) VALUES (
                    :id, :eventId, :code, 'PERCENT', :discount, :validFrom,
                    :validUntil, 100, 3, :exclusive
                )
                ON CONFLICT (code) DO NOTHING
                """).param("id", id).param("eventId", eventId).param("code", code)
            .param("discount", discountBps)
            .param("validFrom", Timestamp.from(now.minus(1, ChronoUnit.DAYS)))
            .param("validUntil", Timestamp.from(now.plus(365, ChronoUnit.DAYS)))
            .param("exclusive", exclusive).update();
    }

    private static UUID demoId(String value) {
        return UUID.nameUUIDFromBytes(("lockstep-demo:" + value).getBytes(StandardCharsets.UTF_8));
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
