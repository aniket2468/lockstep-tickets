package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = SeatHoldIntegrationTest.FixedClockConfiguration.class)
@Testcontainers
class SeatHoldIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-05-01T12:00:00Z");
    private static final String CUSTOMER_ONE_TOKEN = "B".repeat(43);
    private static final String CUSTOMER_TWO_TOKEN = "C".repeat(43);
    private static final String ADMIN_TOKEN = "D".repeat(43);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired JdbcClient jdbcClient;
    @Autowired WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private UUID customerOne;
    private UUID customerTwo;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity()).build();
        jdbcClient.sql("DELETE FROM seat_hold_item").update();
        jdbcClient.sql("DELETE FROM seat_hold").update();
        jdbcClient.sql("DELETE FROM customer_event_limit").update();
        jdbcClient.sql("DELETE FROM event_seat").update();
        jdbcClient.sql("DELETE FROM price_tier").update();
        jdbcClient.sql("DELETE FROM event").update();
        jdbcClient.sql("DELETE FROM seat").update();
        jdbcClient.sql("DELETE FROM section").update();
        jdbcClient.sql("DELETE FROM venue").update();
        jdbcClient.sql("DELETE FROM access_session").update();
        jdbcClient.sql("DELETE FROM login_throttle").update();
        jdbcClient.sql("DELETE FROM account").update();
        customerOne = account("one@example.com", "CUSTOMER", CUSTOMER_ONE_TOKEN);
        customerTwo = account("two@example.com", "CUSTOMER", CUSTOMER_TWO_TOKEN);
        account("admin@example.com", "ADMIN", ADMIN_TOKEN);
    }

    @Test
    void requiresCustomerAuthenticationAndRole() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID seat = seat(fixture, "1", "AVAILABLE", 0);
        mockMvc.perform(create(fixture.eventId(), List.of(seat), null))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(create(fixture.eventId(), List.of(seat), ADMIN_TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        assertThat(count("seat_hold")).isZero();
    }

    @Test
    void createsFixedTenMinuteHoldAndMasksSeatFromCatalog() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID first = seat(fixture, "1", "AVAILABLE", 0);
        UUID second = seat(fixture, "2", "AVAILABLE", 1);

        mockMvc.perform(create(fixture.eventId(), List.of(second, first), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("ACTIVE"))
            .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
            .andExpect(jsonPath("$.expiresAt").value(NOW.plus(10, ChronoUnit.MINUTES).toString()))
            .andExpect(jsonPath("$.seatIds.length()").value(2));

        assertThat(count("seat_hold")).isEqualTo(1);
        assertThat(count("seat_hold_item")).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT active_hold_seat_count FROM customer_event_limit")
            .query(Integer.class).single()).isEqualTo(2);
        mockMvc.perform(auth(get("/api/events/{id}", fixture.eventId()), CUSTOMER_TWO_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.seats[0].availability").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.seats[1].availability").value("UNAVAILABLE"));
    }

    @Test
    void rejectsInvalidSeatSetsAndRollsBackMixedRequests() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID available = seat(fixture, "1", "AVAILABLE", 0);
        UUID blocked = seat(fixture, "2", "BLOCKED", 1);
        Fixture other = event("ON_SALE");
        UUID foreign = seat(other, "1", "AVAILABLE", 0);

        mockMvc.perform(create(fixture.eventId(), List.of(available, available), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isBadRequest());
        mockMvc.perform(create(fixture.eventId(), List.of(available, blocked), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SEATS_UNAVAILABLE"));
        mockMvc.perform(create(fixture.eventId(), List.of(available, foreign), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict());

        assertThat(count("seat_hold")).isZero();
        assertThat(count("seat_hold_item")).isZero();
    }

    @Test
    void enforcesSaleStateOneActiveHoldAndCustomerLimit() throws Exception {
        Fixture published = event("PUBLISHED");
        UUID hiddenSeat = seat(published, "1", "AVAILABLE", 0);
        mockMvc.perform(create(published.eventId(), List.of(hiddenSeat), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EVENT_NOT_ON_SALE"));

        Fixture onSale = event("ON_SALE");
        UUID first = seat(onSale, "1", "AVAILABLE", 0);
        UUID second = seat(onSale, "2", "AVAILABLE", 1);
        mockMvc.perform(create(onSale.eventId(), List.of(first), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isCreated());
        mockMvc.perform(create(onSale.eventId(), List.of(second), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ACTIVE_HOLD_EXISTS"));

        jdbcClient.sql("UPDATE customer_event_limit SET confirmed_ticket_count = 12, active_hold_seat_count = 0")
            .update();
        jdbcClient.sql("UPDATE seat_hold SET state = 'RELEASED', ended_at = created_at").update();
        mockMvc.perform(create(onSale.eventId(), List.of(second), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CUSTOMER_EVENT_LIMIT_EXCEEDED"));
    }

    @Test
    void saleWindowIsStartInclusiveAndEndExclusive() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID target = seat(fixture, "1", "AVAILABLE", 0);
        jdbcClient.sql("UPDATE event SET sales_start_at = :now WHERE id = :id")
            .param("now", Timestamp.from(NOW)).param("id", fixture.eventId()).update();
        mockMvc.perform(create(fixture.eventId(), List.of(target), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isCreated());

        jdbcClient.sql("DELETE FROM seat_hold_item").update();
        jdbcClient.sql("DELETE FROM seat_hold").update();
        jdbcClient.sql("DELETE FROM customer_event_limit").update();
        jdbcClient.sql("UPDATE event SET sales_start_at = :start, sales_end_at = :now WHERE id = :id")
            .param("start", Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)))
            .param("now", Timestamp.from(NOW)).param("id", fixture.eventId()).update();
        mockMvc.perform(create(fixture.eventId(), List.of(target), CUSTOMER_ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EVENT_NOT_ON_SALE"));
    }

    @Test
    void expiredHoldIsLazilyClosedAndSeatCanBeHeldAgain() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID target = seat(fixture, "1", "AVAILABLE", 0);
        Instant start = NOW.minus(11, ChronoUnit.MINUTES);
        UUID oldHold = jdbcClient.sql("""
                INSERT INTO seat_hold (event_id, account_id, created_at, expires_at)
                VALUES (:eventId, :accountId, :created, :expires) RETURNING id
                """).param("eventId", fixture.eventId()).param("accountId", customerOne)
            .param("created", Timestamp.from(start))
            .param("expires", Timestamp.from(NOW.minus(1, ChronoUnit.MINUTES)))
            .query(UUID.class).single();
        jdbcClient.sql("""
                INSERT INTO seat_hold_item VALUES (:holdId, :eventId, :seatId, :started, :ended)
                """).param("holdId", oldHold).param("eventId", fixture.eventId())
            .param("seatId", target).param("started", Timestamp.from(start))
            .param("ended", Timestamp.from(NOW.minus(1, ChronoUnit.MINUTES))).update();

        mockMvc.perform(create(fixture.eventId(), List.of(target), CUSTOMER_TWO_TOKEN))
            .andExpect(status().isCreated());
        assertThat(jdbcClient.sql("SELECT state FROM seat_hold WHERE id = :id")
            .param("id", oldHold).query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void exactlyOneCustomerWinsSameSeatAcrossRepeatedContention() throws Exception {
        Fixture fixture = event("ON_SALE");
        UUID target = seat(fixture, "1", "AVAILABLE", 0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 20; attempt++) {
                jdbcClient.sql("DELETE FROM seat_hold_item").update();
                jdbcClient.sql("DELETE FROM seat_hold").update();
                jdbcClient.sql("DELETE FROM customer_event_limit").update();
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                Callable<Integer> first = contender(fixture.eventId(), target, CUSTOMER_ONE_TOKEN, ready, start);
                Callable<Integer> second = contender(fixture.eventId(), target, CUSTOMER_TWO_TOKEN, ready, start);
                var one = executor.submit(first);
                var two = executor.submit(second);
                ready.await();
                start.countDown();
                assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
                assertThat(count("seat_hold")).isEqualTo(1);
                assertThat(count("seat_hold_item")).isEqualTo(1);
            }
        }
    }

    @Test
    void concurrentHoldsCannotPushOneCustomerPastEventLimit() throws Exception {
        Fixture fixture = event("ON_SALE");
        List<UUID> firstPair = List.of(
            seat(fixture, "1", "AVAILABLE", 0),
            seat(fixture, "2", "AVAILABLE", 1));
        List<UUID> secondPair = List.of(
            seat(fixture, "3", "AVAILABLE", 2),
            seat(fixture, "4", "AVAILABLE", 3));

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 20; attempt++) {
                jdbcClient.sql("DELETE FROM seat_hold_item").update();
                jdbcClient.sql("DELETE FROM seat_hold").update();
                jdbcClient.sql("DELETE FROM customer_event_limit").update();
                jdbcClient.sql("""
                        INSERT INTO customer_event_limit (
                            account_id, event_id, confirmed_ticket_count, active_hold_seat_count
                        ) VALUES (:accountId, :eventId, 10, 0)
                        """)
                    .param("accountId", customerOne)
                    .param("eventId", fixture.eventId())
                    .update();

                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                Callable<HoldAttempt> first = limitContender(
                    fixture.eventId(), firstPair, ready, start);
                Callable<HoldAttempt> second = limitContender(
                    fixture.eventId(), secondPair, ready, start);
                var one = executor.submit(first);
                var two = executor.submit(second);
                ready.await();
                start.countDown();

                List<HoldAttempt> outcomes = List.of(one.get(), two.get());
                assertThat(outcomes).extracting(HoldAttempt::status)
                    .containsExactlyInAnyOrder(201, 409);
                assertThat(outcomes.stream()
                    .filter(result -> result.status() == 409)
                    .map(HoldAttempt::body)
                    .toList())
                    .singleElement()
                    .asString()
                    .contains("CUSTOMER_EVENT_LIMIT_EXCEEDED");
                assertThat(count("seat_hold")).isEqualTo(1);
                assertThat(count("seat_hold_item")).isEqualTo(2);
                assertThat(jdbcClient.sql("""
                        SELECT confirmed_ticket_count + active_hold_seat_count
                        FROM customer_event_limit
                        WHERE account_id = :accountId AND event_id = :eventId
                        """)
                    .param("accountId", customerOne)
                    .param("eventId", fixture.eventId())
                    .query(Integer.class).single()).isEqualTo(12);
            }
        }
    }

    private Callable<Integer> contender(UUID eventId, UUID seatId, String token,
                                        CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(create(eventId, List.of(seatId), token))
                .andReturn().getResponse().getStatus();
        };
    }

    private Callable<HoldAttempt> limitContender(UUID eventId, List<UUID> seatIds,
                                                  CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            var response = mockMvc.perform(create(eventId, seatIds, CUSTOMER_ONE_TOKEN))
                .andReturn().getResponse();
            return new HoldAttempt(response.getStatus(), response.getContentAsString());
        };
    }

    private MockHttpServletRequestBuilder create(UUID eventId, List<UUID> seats, String token) {
        String body = "{\"seatIds\":[" + seats.stream()
            .map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]}";
        var request = post("/api/events/{id}/holds", eventId)
            .contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? request : auth(request, token);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private UUID account(String email, String role, String token) throws Exception {
        UUID id = jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role)
                VALUES (:email, '$2a$12$01234567890123456789012345678901234567890123456789012', :role)
                RETURNING id
                """).param("email", email).param("role", role).query(UUID.class).single();
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8));
        jdbcClient.sql("""
                INSERT INTO access_session (token_hash, account_id, created_at, expires_at)
                VALUES (:hash, :id, :created, :expires)
                """).param("hash", hash).param("id", id)
            .param("created", Timestamp.from(NOW.minusSeconds(1)))
            .param("expires", Timestamp.from(NOW.plus(1, ChronoUnit.DAYS))).update();
        return id;
    }

    private Fixture event(String state) {
        UUID venue = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO venue (id,name,time_zone,address) VALUES (:id,'Venue','UTC','Address')")
            .param("id", venue).update();
        jdbcClient.sql("INSERT INTO section (id,venue_id,name,display_order) VALUES (:id,:venue,'Main',0)")
            .param("id", section).param("venue", venue).update();
        jdbcClient.sql("""
                INSERT INTO event (id,venue_id,name,state,sales_start_at,sales_end_at,starts_at,time_zone,currency)
                VALUES (:id,:venue,'Event',:state,:salesStart,:salesEnd,:starts,'UTC','USD')
                """).param("id", event).param("venue", venue).param("state", state)
            .param("salesStart", Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)))
            .param("salesEnd", Timestamp.from(NOW.plus(1, ChronoUnit.DAYS)))
            .param("starts", Timestamp.from(NOW.plus(2, ChronoUnit.DAYS))).update();
        jdbcClient.sql("INSERT INTO price_tier (id,event_id,name,price_minor,currency) VALUES (:id,:event,'Standard',5000,'USD')")
            .param("id", tier).param("event", event).update();
        return new Fixture(event, section, tier);
    }

    private UUID seat(Fixture fixture, String number, String state, int order) {
        UUID source = UUID.randomUUID();
        UUID eventSeat = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO seat (id,section_id,row_label,seat_number,display_order) VALUES (:id,:section,'A',:number,:order)")
            .param("id", source).param("section", fixture.sectionId())
            .param("number", number).param("order", order).update();
        jdbcClient.sql("""
                INSERT INTO event_seat (id,event_id,source_seat_id,price_tier_id,section_name,row_label,seat_number,display_order,accessible,inventory_state)
                VALUES (:id,:event,:source,:tier,'Main','A',:number,:order,false,:state)
                """).param("id", eventSeat).param("event", fixture.eventId())
            .param("source", source).param("tier", fixture.tierId())
            .param("number", number).param("order", order).param("state", state).update();
        return eventSeat;
    }

    private int count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(UUID eventId, UUID sectionId, UUID tierId) {}
    private record HoldAttempt(int status, String body) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
