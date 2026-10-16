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
import java.util.UUID;
import java.util.List;
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
import com.locksteptickets.checkout.MockPaymentGateway;
import com.locksteptickets.checkout.PaymentReconciliationWorker;

@SpringBootTest(
    classes = CheckoutIntegrationTest.FixedClockConfiguration.class,
    properties = {
        "lockstep.payment-reconciliation.enabled=true",
        "lockstep.payment-reconciliation.batch-size=2"
    }
)
@Testcontainers
class CheckoutIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-05-01T12:00:00Z");
    private static final String ONE_TOKEN = "E".repeat(43);
    private static final String TWO_TOKEN = "F".repeat(43);

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MockPaymentGateway gateway;
    @Autowired PaymentReconciliationWorker reconciliationWorker;
    private MockMvc mvc;
    private UUID customerOne;
    private UUID customerTwo;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        gateway.clearSimulations();
        for (String table : new String[]{"outbox_event", "promo_redemption", "payment_attempt",
            "ticket", "booking", "seat_hold_item", "seat_hold", "customer_event_limit",
            "promo_code", "event_seat", "price_tier", "event", "seat", "section", "venue",
            "access_session", "login_throttle", "account"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        customerOne = account("one@checkout.test", ONE_TOKEN);
        customerTwo = account("two@checkout.test", TWO_TOKEN);
    }

    @Test
    void freezesExactQuoteAndConfirmsExactlyOnce() throws Exception {
        Fixture fixture = event(101, 1000, 2, 500);
        UUID first = seat(fixture, "1", 1001, 0);
        UUID second = seat(fixture, "2", 2003, 1);
        promo(fixture.eventId(), "SAVE333", "FIXED", 333, 10, 1, false);
        UUID hold = hold(fixture.eventId(), first, second, "save333");

        assertThat(jdbc.sql("SELECT total_minor FROM seat_hold WHERE id=:id")
            .param("id", hold).query(Long.class).single()).isEqualTo(2337);
        jdbc.sql("UPDATE price_tier SET price_minor = 999999 WHERE event_id=:id")
            .param("id", fixture.eventId()).update();

        mvc.perform(checkout(hold, "checkout-key-0001", "SUCCESS", "UNKNOWN", ONE_TOKEN))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("CONFIRMED"))
            .andExpect(jsonPath("$.totalMinor").value(2337));
        mvc.perform(checkout(hold, "checkout-key-0001", "SUCCESS", "UNKNOWN", ONE_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));

        assertThat(count("booking")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(count("ticket")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sum(charged_total_minor) FROM ticket")
            .query(Long.class).single()).isEqualTo(2337);
        assertThat(jdbc.sql("SELECT state FROM promo_redemption")
            .query(String.class).single()).isEqualTo("CONSUMED");
    }

    @Test
    void declineReleasesSeatAndIdempotentReplayReturnsOriginalResult() throws Exception {
        Fixture fixture = event(0, 0, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        UUID hold = hold(fixture.eventId(), target, null, null);

        mvc.perform(checkout(hold, "checkout-key-0002", "DECLINE", "UNKNOWN", ONE_TOKEN))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("PAYMENT_FAILED"));
        mvc.perform(checkout(hold, "checkout-key-0002", "SUCCESS", "UNKNOWN", ONE_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAYMENT_FAILED"));
        assertThat(count("ticket")).isZero();
        assertThat(jdbc.sql("SELECT state FROM seat_hold WHERE id=:id").param("id", hold)
            .query(String.class).single()).isEqualTo("RELEASED");
        mvc.perform(createHold(fixture.eventId(), target, null, TWO_TOKEN))
            .andExpect(status().isCreated());
    }

    @Test
    void unknownProtectsInventoryUntilStatusLookupResolves() throws Exception {
        Fixture fixture = event(0, 0, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        UUID hold = hold(fixture.eventId(), target, null, null);
        String body = mvc.perform(checkout(hold, "checkout-key-0003", "TIMEOUT", "SUCCESS", ONE_TOKEN))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("PAYMENT_UNKNOWN"))
            .andReturn().getResponse().getContentAsString();
        String bookingId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(createHold(fixture.eventId(), target, null, TWO_TOKEN))
            .andExpect(status().isConflict());
        mvc.perform(auth(get("/api/events/{id}", fixture.eventId()), TWO_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.seats[0].availability").value("UNAVAILABLE"));
        mvc.perform(auth(post("/api/bookings/{id}/reconcile", UUID.fromString(bookingId)), TWO_TOKEN))
            .andExpect(status().isNotFound());

        mvc.perform(auth(post("/api/bookings/{id}/reconcile", UUID.fromString(bookingId)), ONE_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        assertThat(count("ticket")).isEqualTo(1);
    }

    @Test
    void scheduledWorkerReconcilesOnlyStalePendingPaymentsInBoundedRuns() throws Exception {
        UUID durableSuccess = unknownBooking("worker-durable-key", "UNKNOWN");
        UUID lookupSuccess = unknownBooking("worker-success-key", "SUCCESS");
        UUID decline = unknownBooking("worker-decline-key", "DECLINE");
        UUID unknown = unknownBooking("worker-unknown-key", "UNKNOWN");
        UUID fresh = unknownBooking("worker-fresh-key", "SUCCESS");
        jdbc.sql("""
                UPDATE payment_attempt
                SET updated_at = :stale
                WHERE booking_id IN (:durableSuccess, :lookupSuccess, :decline, :unknown)
                """)
            .param("stale", Timestamp.from(NOW.minusSeconds(31)))
            .param("durableSuccess", durableSuccess)
            .param("lookupSuccess", lookupSuccess)
            .param("decline", decline)
            .param("unknown", unknown)
            .update();
        jdbc.sql("""
                UPDATE payment_attempt
                SET state='SUCCEEDED', observed_outcome='SUCCESS'
                WHERE booking_id=:bookingId
                """)
            .param("bookingId", durableSuccess)
            .update();

        var firstRun = reconciliationWorker.runOnce();
        var secondRun = reconciliationWorker.runOnce();

        assertThat(firstRun.selected()).isEqualTo(2);
        assertThat(secondRun.selected()).isEqualTo(2);
        assertThat(firstRun.resolved() + secondRun.resolved()).isEqualTo(3);
        assertThat(firstRun.stillPending() + secondRun.stillPending()).isEqualTo(1);
        assertThat(firstRun.failed() + secondRun.failed()).isZero();
        assertThat(bookingState(durableSuccess)).isEqualTo("CONFIRMED");
        assertThat(bookingState(lookupSuccess)).isEqualTo("CONFIRMED");
        assertThat(bookingState(decline)).isEqualTo("PAYMENT_FAILED");
        assertThat(bookingState(unknown)).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(bookingState(fresh)).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(jdbc.sql("SELECT count(*) FROM ticket WHERE booking_id IN (:one,:two)")
            .param("one", durableSuccess).param("two", lookupSuccess)
            .query(Integer.class).single()).isEqualTo(2);
        assertThat(reconciliationWorker.runOnce())
            .isEqualTo(new PaymentReconciliationWorker.RunResult(0, 0, 0, 0));
    }

    @Test
    void expiredAndForeignHoldsNeverCreatePaymentAttempts() throws Exception {
        Fixture fixture = event(0, 0, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        UUID hold = hold(fixture.eventId(), target, null, null);
        jdbc.sql("UPDATE seat_hold SET created_at=:created, expires_at=:now WHERE id=:id")
            .param("created", Timestamp.from(NOW.minus(10, ChronoUnit.MINUTES)))
            .param("now", Timestamp.from(NOW)).param("id", hold).update();

        mvc.perform(checkout(hold, "checkout-key-0004", "SUCCESS", "UNKNOWN", ONE_TOKEN))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HOLD_NOT_ACTIVE"));
        mvc.perform(checkout(hold, "checkout-key-0005", "SUCCESS", "UNKNOWN", TWO_TOKEN))
            .andExpect(status().isNotFound());
        assertThat(count("payment_attempt")).isZero();
    }

    @Test
    void rejectsMassAssignmentAndExclusivePromoCombinations() throws Exception {
        Fixture fixture = event(0, 100, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        promo(fixture.eventId(), "ONLY", "PERCENT", 1000, null, null, true);
        mvc.perform(createHold(fixture.eventId(), target, "ONLY", ONE_TOKEN))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROMO_NOT_COMBINABLE"));

        Fixture plain = event(0, 0, null, 0);
        UUID plainSeat = seat(plain, "1", 5000, 0);
        UUID hold = hold(plain.eventId(), plainSeat, null, null);
        mvc.perform(auth(post("/api/holds/{id}/checkout", hold)
                .header("Idempotency-Key", "checkout-key-0006")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentOutcome\":\"SUCCESS\",\"totalMinor\":1}"), ONE_TOKEN))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
        assertThat(count("payment_attempt")).isZero();
    }

    @Test
    void globalPromoLimitIsAtomicAcrossConcurrentCheckouts() throws Exception {
        Fixture firstEvent = event(0, 0, null, 0);
        Fixture secondEvent = event(0, 0, null, 0);
        UUID firstSeat = seat(firstEvent, "1", 5000, 0);
        UUID secondSeat = seat(secondEvent, "1", 5000, 0);
        promo(null, "LASTONE", "FIXED", 100, 1, null, false);
        mvc.perform(createHold(firstEvent.eventId(), firstSeat, "LASTONE", ONE_TOKEN))
            .andExpect(status().isCreated());
        mvc.perform(createHold(secondEvent.eventId(), secondSeat, "LASTONE", TWO_TOKEN))
            .andExpect(status().isCreated());
        UUID firstHold = jdbc.sql("SELECT id FROM seat_hold WHERE event_id=:id")
            .param("id", firstEvent.eventId()).query(UUID.class).single();
        UUID secondHold = jdbc.sql("SELECT id FROM seat_hold WHERE event_id=:id")
            .param("id", secondEvent.eventId()).query(UUID.class).single();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Callable<Integer> one = checkoutContender(firstHold, "concurrent-key-01", ONE_TOKEN, ready, start);
            Callable<Integer> two = checkoutContender(secondHold, "concurrent-key-02", TWO_TOKEN, ready, start);
            var first = executor.submit(one);
            var second = executor.submit(two);
            ready.await();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(count("promo_redemption")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT redeemed_count FROM promo_code WHERE code='LASTONE'")
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreatesOnlyOnePaymentAttempt() throws Exception {
        Fixture fixture = event(0, 0, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        UUID hold = hold(fixture.eventId(), target, null, null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Callable<Integer> first = checkoutContender(
                hold, "same-checkout-key", ONE_TOKEN, ready, start);
            Callable<Integer> second = checkoutContender(
                hold, "same-checkout-key", ONE_TOKEN, ready, start);
            var one = executor.submit(first);
            var two = executor.submit(second);
            ready.await();
            start.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 200);
        }
        assertThat(count("booking")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(count("ticket")).isEqualTo(1);
    }

    private Callable<Integer> checkoutContender(UUID hold, String key, String token,
                                                 CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return mvc.perform(checkout(hold, key, "SUCCESS", "UNKNOWN", token))
                .andReturn().getResponse().getStatus();
        };
    }

    private UUID unknownBooking(String key, String lookup) throws Exception {
        Fixture fixture = event(0, 0, null, 0);
        UUID target = seat(fixture, "1", 5000, 0);
        UUID hold = hold(fixture.eventId(), target, null, null);
        mvc.perform(checkout(hold, key, "TIMEOUT", lookup, ONE_TOKEN))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PAYMENT_UNKNOWN"));
        return jdbc.sql("SELECT id FROM booking WHERE checkout_key=:key")
            .param("key", key).query(UUID.class).single();
    }

    private String bookingState(UUID bookingId) {
        return jdbc.sql("SELECT state FROM booking WHERE id=:id")
            .param("id", bookingId).query(String.class).single();
    }

    private UUID hold(UUID eventId, UUID first, UUID second, String promo) throws Exception {
        mvc.perform(createHold(eventId, first, second, promo, ONE_TOKEN)).andExpect(status().isCreated());
        return jdbc.sql("""
                SELECT id FROM seat_hold
                WHERE event_id=:eventId AND account_id=:accountId AND state='ACTIVE'
                """)
            .param("eventId", eventId).param("accountId", customerOne)
            .query(UUID.class).single();
    }

    private MockHttpServletRequestBuilder createHold(UUID eventId, UUID seat, String promo, String token) {
        return createHold(eventId, seat, null, promo, token);
    }

    private MockHttpServletRequestBuilder createHold(UUID eventId, UUID first, UUID second,
                                                       String promo, String token) {
        String seats = "\"" + first + "\"" + (second == null ? "" : ",\"" + second + "\"");
        String promoJson = promo == null ? "" : ",\"promoCode\":\"" + promo + "\"";
        return auth(post("/api/events/{id}/holds", eventId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"seatIds\":[" + seats + "]" + promoJson + "}"), token);
    }

    private MockHttpServletRequestBuilder checkout(UUID hold, String key, String outcome,
                                                     String lookup, String token) {
        gateway.simulateOnce(key, MockPaymentGateway.RequestedOutcome.valueOf(outcome),
            MockPaymentGateway.LookupOutcome.valueOf(lookup));
        return auth(post("/api/holds/{id}/checkout", hold).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content("{}"), token);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private UUID account(String email, String token) throws Exception {
        UUID id = jdbc.sql("""
                INSERT INTO account (email,password_hash,role)
                VALUES (:email,'$2a$12$01234567890123456789012345678901234567890123456789012','CUSTOMER')
                RETURNING id
                """).param("email", email).query(UUID.class).single();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO access_session (token_hash,account_id,created_at,expires_at) VALUES (:hash,:id,:now,:end)")
            .param("hash", hash).param("id", id).param("now", Timestamp.from(NOW.minusSeconds(1)))
            .param("end", Timestamp.from(NOW.plus(1, ChronoUnit.DAYS))).update();
        return id;
    }

    private Fixture event(long fee, int earlyBps, Integer groupThreshold, int groupBps) {
        UUID venue = UUID.randomUUID(), section = UUID.randomUUID(), event = UUID.randomUUID();
        jdbc.sql("INSERT INTO venue(id,name,time_zone,address) VALUES(:id,'V','UTC','A')").param("id", venue).update();
        jdbc.sql("INSERT INTO section(id,venue_id,name,display_order) VALUES(:id,:venue,'S',0)")
            .param("id", section).param("venue", venue).update();
        jdbc.sql("""
                INSERT INTO event(id,venue_id,name,state,sales_start_at,sales_end_at,starts_at,
                    time_zone,currency,service_fee_minor,early_bird_ends_at,
                    early_bird_discount_bps,group_size_threshold,group_discount_bps)
                VALUES(:id,:venue,'E','ON_SALE',:start,:end,:eventStart,'UTC','USD',:fee,
                    :earlyEnd,:early,:threshold,:groupDiscount)
                """).param("id", event).param("venue", venue)
            .param("start", Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)))
            .param("end", Timestamp.from(NOW.plus(1, ChronoUnit.DAYS)))
            .param("eventStart", Timestamp.from(NOW.plus(10, ChronoUnit.DAYS)))
            .param("fee", fee).param("earlyEnd", Timestamp.from(NOW.plusSeconds(1)))
            .param("early", earlyBps).param("threshold", groupThreshold)
            .param("groupDiscount", groupBps).update();
        return new Fixture(event, section);
    }

    private UUID seat(Fixture fixture, String number, long price, int order) {
        UUID source = UUID.randomUUID(), tier = UUID.randomUUID(), eventSeat = UUID.randomUUID();
        jdbc.sql("INSERT INTO seat(id,section_id,row_label,seat_number,display_order) VALUES(:id,:section,'A',:number,:ord)")
            .param("id", source).param("section", fixture.sectionId()).param("number", number).param("ord", order).update();
        jdbc.sql("INSERT INTO price_tier(id,event_id,name,price_minor,currency) VALUES(:id,:event,:name,:price,'USD')")
            .param("id", tier).param("event", fixture.eventId()).param("name", "T" + number).param("price", price).update();
        jdbc.sql("""
                INSERT INTO event_seat(id,event_id,source_seat_id,price_tier_id,section_name,
                    row_label,seat_number,display_order,accessible,inventory_state)
                VALUES(:id,:event,:source,:tier,'S','A',:number,:ord,false,'AVAILABLE')
                """).param("id", eventSeat).param("event", fixture.eventId()).param("source", source)
            .param("tier", tier).param("number", number).param("ord", order).update();
        return eventSeat;
    }

    private void promo(UUID eventId, String code, String type, long value,
                       Integer global, Integer customer, boolean exclusive) {
        jdbc.sql("""
                INSERT INTO promo_code(event_id,code,discount_type,discount_value,valid_from,
                    valid_until,global_limit,per_customer_limit,exclusive)
                VALUES(:event,:code,:type,:value,:start,:end,:global,:customer,:exclusive)
                """).param("event", eventId).param("code", code).param("type", type)
            .param("value", value).param("start", Timestamp.from(NOW.minusSeconds(1)))
            .param("end", Timestamp.from(NOW.plus(1, ChronoUnit.DAYS))).param("global", global)
            .param("customer", customer).param("exclusive", exclusive).update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private record Fixture(UUID eventId, UUID sectionId) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
