package com.locksteptickets.catalog;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {

    private static final String CUSTOMER_VISIBLE_STATES =
        "('PUBLISHED', 'ON_SALE', 'SALES_CLOSED')";

    private final JdbcClient jdbcClient;

    public CatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<EventSummaryRow> findEvents(String query, int limit, int offset) {
        return jdbcClient.sql("""
                SELECT e.id, e.name, e.state, e.starts_at, e.time_zone,
                       e.currency, e.service_fee_minor,
                       v.name AS venue_name, v.address AS venue_address,
                       MIN(pt.price_minor) FILTER (WHERE pt.retired_at IS NULL) AS from_price_minor
                FROM event e
                JOIN venue v ON v.id = e.venue_id
                LEFT JOIN price_tier pt ON pt.event_id = e.id
                WHERE e.retired_at IS NULL
                  AND v.retired_at IS NULL
                  AND e.state IN """ + CUSTOMER_VISIBLE_STATES + """
                  AND (:query = '' OR position(lower(:query) in lower(e.name)) > 0)
                GROUP BY e.id, v.id
                ORDER BY e.starts_at, e.id
                LIMIT :limit OFFSET :offset
                """)
            .param("query", query)
            .param("limit", limit)
            .param("offset", offset)
            .query((resultSet, rowNumber) -> new EventSummaryRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("state"),
                resultSet.getTimestamp("starts_at").toInstant(),
                resultSet.getString("time_zone"),
                resultSet.getString("currency").strip(),
                resultSet.getLong("service_fee_minor"),
                resultSet.getString("venue_name"),
                resultSet.getString("venue_address"),
                (Long) resultSet.getObject("from_price_minor")))
            .list();
    }

    public Optional<EventSummaryRow> findEvent(UUID eventId) {
        return jdbcClient.sql("""
                SELECT e.id, e.name, e.state, e.starts_at, e.time_zone,
                       e.currency, e.service_fee_minor,
                       v.name AS venue_name, v.address AS venue_address,
                       MIN(pt.price_minor) FILTER (WHERE pt.retired_at IS NULL) AS from_price_minor
                FROM event e
                JOIN venue v ON v.id = e.venue_id
                LEFT JOIN price_tier pt ON pt.event_id = e.id
                WHERE e.id = :eventId
                  AND e.retired_at IS NULL
                  AND v.retired_at IS NULL
                  AND e.state IN """ + CUSTOMER_VISIBLE_STATES + """
                GROUP BY e.id, v.id
                """)
            .param("eventId", eventId)
            .query((resultSet, rowNumber) -> new EventSummaryRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("state"),
                resultSet.getTimestamp("starts_at").toInstant(),
                resultSet.getString("time_zone"),
                resultSet.getString("currency").strip(),
                resultSet.getLong("service_fee_minor"),
                resultSet.getString("venue_name"),
                resultSet.getString("venue_address"),
                (Long) resultSet.getObject("from_price_minor")))
            .optional();
    }

    public List<EventSeatRow> findSeats(UUID eventId, Instant now) {
        return jdbcClient.sql("""
                SELECT es.id, es.section_name, es.row_label, es.seat_number,
                       es.accessible, es.inventory_state,
                       pt.name AS tier_name, pt.price_minor, pt.currency,
                       (EXISTS (
                           SELECT 1
                           FROM seat_hold_item hi JOIN seat_hold h ON h.id = hi.hold_id
                           WHERE hi.event_seat_id = es.id
                             AND ((h.state = 'ACTIVE' AND h.expires_at > :now)
                               OR h.state = 'PAYMENT_PENDING')
                       ) OR EXISTS (
                           SELECT 1 FROM ticket t
                           WHERE t.event_seat_id = es.id
                             AND t.state IN ('CONFIRMED', 'CANCELLATION_PENDING')
                       )) AS actively_held
                FROM event_seat es
                JOIN price_tier pt
                  ON pt.id = es.price_tier_id AND pt.event_id = es.event_id
                WHERE es.event_id = :eventId
                ORDER BY es.display_order, es.id
                """)
            .param("eventId", eventId).param("now", Timestamp.from(now))
            .query((resultSet, rowNumber) -> new EventSeatRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("section_name"),
                resultSet.getString("row_label"),
                resultSet.getString("seat_number"),
                resultSet.getBoolean("accessible"),
                resultSet.getString("inventory_state"),
                resultSet.getString("tier_name"),
                resultSet.getLong("price_minor"),
                resultSet.getString("currency").strip(),
                resultSet.getBoolean("actively_held")))
            .list();
    }

    public record EventSummaryRow(
        UUID id,
        String name,
        String state,
        Instant startsAt,
        String timeZone,
        String currency,
        long serviceFeeMinor,
        String venueName,
        String venueAddress,
        Long fromPriceMinor
    ) {
    }

    public record EventSeatRow(
        UUID id,
        String sectionName,
        String rowLabel,
        String seatNumber,
        boolean accessible,
        String inventoryState,
        String tierName,
        long priceMinor,
        String currency,
        boolean activelyHeld
    ) {
    }
}
