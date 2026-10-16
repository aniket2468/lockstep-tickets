package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Testcontainers
class CatalogApiIntegrationTest {

    private static final String TOKEN = "A".repeat(43);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
        jdbcClient.sql("DELETE FROM event_seat").update();
        jdbcClient.sql("DELETE FROM price_tier").update();
        jdbcClient.sql("DELETE FROM event").update();
        jdbcClient.sql("DELETE FROM seat").update();
        jdbcClient.sql("DELETE FROM section").update();
        jdbcClient.sql("DELETE FROM venue").update();
        jdbcClient.sql("DELETE FROM access_session").update();
        jdbcClient.sql("DELETE FROM login_throttle").update();
        jdbcClient.sql("DELETE FROM account").update();
        insertAuthenticatedCustomer();
    }

    @Test
    void requiresAuthenticationForCatalogAndDetails() throws Exception {
        mockMvc.perform(get("/api/events"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/events/{id}", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void listsOnlyVisibleEventsInDeterministicBoundedPages() throws Exception {
        Instant base = Instant.parse("2030-06-01T18:00:00Z");
        Fixture later = createEvent("Later Show", "PUBLISHED", base.plus(2, ChronoUnit.DAYS));
        Fixture earlier = createEvent("Earlier Show", "ON_SALE", base.plus(1, ChronoUnit.DAYS));
        createEvent("Private Rehearsal", "DRAFT", base);
        Fixture retired = createEvent("Retired Show", "ON_SALE", base.plus(3, ChronoUnit.DAYS));
        jdbcClient.sql("UPDATE event SET retired_at = current_timestamp WHERE id = :id")
            .param("id", retired.eventId()).update();

        mockMvc.perform(authenticated(get("/api/events")).param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(earlier.eventId().toString()))
            .andExpect(jsonPath("$.items[0].name").value("Earlier Show"))
            .andExpect(jsonPath("$.items[0].fromPrice.amountMinor").value(5000))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.limit").value(1))
            .andExpect(jsonPath("$.offset").value(0));

        mockMvc.perform(authenticated(get("/api/events"))
                .param("limit", "1").param("offset", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(later.eventId().toString()))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void treatsSearchMetacharactersAsLiteralDataAndValidatesBounds() throws Exception {
        createEvent("100% Live_Show", "ON_SALE", Instant.parse("2030-06-01T18:00:00Z"));

        mockMvc.perform(authenticated(get("/api/events")).param("query", "% Live_"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(authenticated(get("/api/events")).param("query", "' OR 1=1 --"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());

        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
            authenticated(get("/api/events")).param("limit", "51"),
            authenticated(get("/api/events")).param("offset", "5001"),
            authenticated(get("/api/events")).param("query", "x".repeat(101))
        }) {
            mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void returnsSnapshotSeatsAndMasksInternalInventoryReasons() throws Exception {
        Fixture event = createEvent(
            "Seat Test", "ON_SALE", Instant.parse("2030-06-01T18:00:00Z"));
        UUID availableSeat = addSeat(event, "A", "1", true, "AVAILABLE", 0);
        UUID blockedSeat = addSeat(event, "A", "2", false, "BLOCKED", 1);

        MvcResult result = mockMvc.perform(authenticated(
                get("/api/events/{id}", event.eventId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Seat Test"))
            .andExpect(jsonPath("$.serviceFee.amountMinor").value(250))
            .andExpect(jsonPath("$.seats[0].id").value(availableSeat.toString()))
            .andExpect(jsonPath("$.seats[0].availability").value("AVAILABLE"))
            .andExpect(jsonPath("$.seats[0].accessible").value(true))
            .andExpect(jsonPath("$.seats[0].price.amountMinor").value(5000))
            .andExpect(jsonPath("$.seats[1].id").value(blockedSeat.toString()))
            .andExpect(jsonPath("$.seats[1].availability").value("UNAVAILABLE"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("sourceSeatId", "inventoryState", "BLOCKED", "version");
    }

    @Test
    void hidesUnavailableEventsAndReturnsStableParameterErrors() throws Exception {
        Fixture draft = createEvent(
            "Draft", "DRAFT", Instant.parse("2030-06-01T18:00:00Z"));
        Fixture retiredVenue = createEvent(
            "Hidden Venue", "ON_SALE", Instant.parse("2030-06-02T18:00:00Z"));
        jdbcClient.sql("UPDATE venue SET retired_at = current_timestamp WHERE id = :id")
            .param("id", retiredVenue.venueId()).update();

        for (UUID id : new UUID[] {draft.eventId(), retiredVenue.eventId(), UUID.randomUUID()}) {
            mockMvc.perform(authenticated(get("/api/events/{id}", id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
        mockMvc.perform(authenticated(get("/api/events/not-a-uuid")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void publishedSeatsRemainUnavailableUntilEventIsOnSale() throws Exception {
        Fixture event = createEvent(
            "Coming Soon", "PUBLISHED", Instant.parse("2030-06-01T18:00:00Z"));
        addSeat(event, "A", "1", false, "AVAILABLE", 0);

        mockMvc.perform(authenticated(get("/api/events/{id}", event.eventId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.seats[0].availability").value("UNAVAILABLE"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }

    private void insertAuthenticatedCustomer() throws Exception {
        UUID accountId = jdbcClient.sql("""
                INSERT INTO account (email, password_hash, role)
                VALUES (
                    'catalog@example.com',
                    '$2a$12$01234567890123456789012345678901234567890123456789012',
                    'CUSTOMER'
                ) RETURNING id
                """).query(UUID.class).single();
        byte[] tokenHash = MessageDigest.getInstance("SHA-256")
            .digest(TOKEN.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        jdbcClient.sql("""
                INSERT INTO access_session (token_hash, account_id, created_at, expires_at)
                VALUES (:tokenHash, :accountId, :createdAt, :expiresAt)
                """)
            .param("tokenHash", tokenHash)
            .param("accountId", accountId)
            .param("createdAt", Timestamp.from(now.minusSeconds(10)))
            .param("expiresAt", Timestamp.from(now.plusSeconds(600)))
            .update();
    }

    private Fixture createEvent(String name, String state, Instant startsAt) {
        UUID venueId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO venue (id, name, time_zone, address)
                VALUES (:id, :name, 'America/Indiana/Indianapolis', '1 Test Way')
                """).param("id", venueId).param("name", name + " Venue").update();
        jdbcClient.sql("""
                INSERT INTO section (id, venue_id, name, display_order)
                VALUES (:id, :venueId, 'Orchestra', 0)
                """).param("id", sectionId).param("venueId", venueId).update();
        jdbcClient.sql("""
                INSERT INTO event (
                    id, venue_id, name, state, sales_start_at, sales_end_at,
                    starts_at, time_zone, currency, service_fee_minor
                ) VALUES (
                    :id, :venueId, :name, :state, :salesStart, :salesEnd,
                    :startsAt, 'America/Indiana/Indianapolis', 'USD', 250
                )
                """)
            .param("id", eventId).param("venueId", venueId)
            .param("name", name).param("state", state)
            .param("salesStart", Timestamp.from(startsAt.minus(30, ChronoUnit.DAYS)))
            .param("salesEnd", Timestamp.from(startsAt.minus(1, ChronoUnit.DAYS)))
            .param("startsAt", Timestamp.from(startsAt)).update();
        jdbcClient.sql("""
                INSERT INTO price_tier (id, event_id, name, price_minor, currency)
                VALUES (:id, :eventId, 'Standard', 5000, 'USD')
                """).param("id", tierId).param("eventId", eventId).update();
        return new Fixture(eventId, venueId, sectionId, tierId);
    }

    private UUID addSeat(
        Fixture fixture,
        String row,
        String number,
        boolean accessible,
        String inventoryState,
        int displayOrder
    ) {
        UUID sourceSeatId = UUID.randomUUID();
        UUID eventSeatId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO seat (
                    id, section_id, row_label, seat_number, display_order, accessible
                ) VALUES (:id, :sectionId, :row, :number, :displayOrder, :accessible)
                """)
            .param("id", sourceSeatId).param("sectionId", fixture.sectionId())
            .param("row", row).param("number", number)
            .param("displayOrder", displayOrder).param("accessible", accessible).update();
        jdbcClient.sql("""
                INSERT INTO event_seat (
                    id, event_id, source_seat_id, price_tier_id, section_name,
                    row_label, seat_number, display_order, accessible, inventory_state
                ) VALUES (
                    :id, :eventId, :sourceSeatId, :tierId, 'Orchestra',
                    :row, :number, :displayOrder, :accessible, :inventoryState
                )
                """)
            .param("id", eventSeatId).param("eventId", fixture.eventId())
            .param("sourceSeatId", sourceSeatId).param("tierId", fixture.tierId())
            .param("row", row).param("number", number)
            .param("displayOrder", displayOrder).param("accessible", accessible)
            .param("inventoryState", inventoryState).update();
        return eventSeatId;
    }

    private record Fixture(UUID eventId, UUID venueId, UUID sectionId, UUID tierId) {
    }
}
