package com.locksteptickets.hold;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HoldRepository {

    private final JdbcClient jdbcClient;

    public HoldRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<EventForHold> lockVisibleEvent(UUID eventId) {
        return jdbcClient.sql("""
                SELECT id, state, sales_start_at, sales_end_at
                FROM event
                WHERE id = :eventId
                  AND retired_at IS NULL
                  AND state IN ('PUBLISHED', 'ON_SALE', 'SALES_CLOSED')
                FOR UPDATE
                """)
            .param("eventId", eventId)
            .query((rs, row) -> new EventForHold(
                rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getTimestamp("sales_start_at").toInstant(),
                rs.getTimestamp("sales_end_at").toInstant()))
            .optional();
    }

    void expireEventHolds(UUID eventId, Instant now) {
        jdbcClient.sql("""
                UPDATE seat_hold
                SET state = 'EXPIRED', ended_at = expires_at, version = version + 1
                WHERE event_id = :eventId AND state = 'ACTIVE' AND expires_at <= :now
                """)
            .param("eventId", eventId).param("now", Timestamp.from(now)).update();
    }

    void ensureAndLockLimit(UUID accountId, UUID eventId) {
        jdbcClient.sql("""
                INSERT INTO customer_event_limit (account_id, event_id)
                VALUES (:accountId, :eventId)
                ON CONFLICT (account_id, event_id) DO NOTHING
                """)
            .param("accountId", accountId).param("eventId", eventId).update();
        jdbcClient.sql("""
                SELECT account_id FROM customer_event_limit
                WHERE account_id = :accountId AND event_id = :eventId
                FOR UPDATE
                """)
            .param("accountId", accountId).param("eventId", eventId)
            .query(UUID.class).single();
    }

    int refreshAndGetTotal(UUID accountId, UUID eventId, Instant now) {
        return jdbcClient.sql("""
                UPDATE customer_event_limit l
                SET active_hold_seat_count = (
                        SELECT count(*)
                        FROM seat_hold h JOIN seat_hold_item i ON i.hold_id = h.id
                        WHERE h.account_id = l.account_id AND h.event_id = l.event_id
                          AND h.state = 'ACTIVE' AND h.expires_at > :now
                    ),
                    version = version + 1
                WHERE account_id = :accountId AND event_id = :eventId
                RETURNING active_hold_seat_count + confirmed_ticket_count
                """)
            .param("accountId", accountId).param("eventId", eventId)
            .param("now", Timestamp.from(now)).query(Integer.class).single();
    }

    boolean hasActiveHold(UUID accountId, UUID eventId, Instant now) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM seat_hold
                    WHERE account_id = :accountId AND event_id = :eventId
                      AND state = 'ACTIVE' AND expires_at > :now
                )
                """)
            .param("accountId", accountId).param("eventId", eventId)
            .param("now", Timestamp.from(now)).query(Boolean.class).single();
    }

    List<SeatForHold> lockSeats(UUID eventId, List<UUID> seatIds) {
        return jdbcClient.sql("""
                SELECT id, inventory_state FROM event_seat
                WHERE event_id = :eventId AND id IN (:seatIds)
                ORDER BY id
                FOR UPDATE
                """)
            .param("eventId", eventId).param("seatIds", seatIds)
            .query((rs, row) -> new SeatForHold(
                rs.getObject("id", UUID.class), rs.getString("inventory_state")))
            .list();
    }

    boolean anyActivelyHeld(List<UUID> seatIds, Instant now) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM seat_hold_item i JOIN seat_hold h ON h.id = i.hold_id
                    WHERE i.event_seat_id IN (:seatIds)
                      AND h.state = 'ACTIVE' AND h.expires_at > :now
                )
                """)
            .param("seatIds", seatIds).param("now", Timestamp.from(now))
            .query(Boolean.class).single();
    }

    UUID insertHold(UUID eventId, UUID accountId, Instant now, Instant expiresAt) {
        return jdbcClient.sql("""
                INSERT INTO seat_hold (event_id, account_id, created_at, expires_at)
                VALUES (:eventId, :accountId, :now, :expiresAt)
                RETURNING id
                """)
            .param("eventId", eventId).param("accountId", accountId)
            .param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
            .query(UUID.class).single();
    }

    void insertItems(UUID holdId, UUID eventId, List<UUID> seatIds, Instant now, Instant expiresAt) {
        for (UUID seatId : seatIds) {
            jdbcClient.sql("""
                    INSERT INTO seat_hold_item (
                        hold_id, event_id, event_seat_id, claim_started_at, claim_ends_at
                    ) VALUES (:holdId, :eventId, :seatId, :now, :expiresAt)
                    """)
                .param("holdId", holdId).param("eventId", eventId).param("seatId", seatId)
                .param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
                .update();
        }
    }

    void setActiveCount(UUID accountId, UUID eventId, int count) {
        jdbcClient.sql("""
                UPDATE customer_event_limit
                SET active_hold_seat_count = :count, version = version + 1
                WHERE account_id = :accountId AND event_id = :eventId
                """)
            .param("count", count).param("accountId", accountId).param("eventId", eventId)
            .update();
    }

    record EventForHold(UUID id, String state, Instant salesStartAt, Instant salesEndAt) {}
    record SeatForHold(UUID id, String inventoryState) {}
}
