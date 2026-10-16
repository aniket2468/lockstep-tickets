package com.locksteptickets.hold;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.locksteptickets.pricing.PricingPolicy;

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
                SELECT id, state, sales_start_at, sales_end_at, currency,
                       service_fee_minor, early_bird_ends_at, early_bird_discount_bps,
                       group_size_threshold, group_discount_bps
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
                rs.getTimestamp("sales_end_at").toInstant(), rs.getString("currency").strip(),
                rs.getLong("service_fee_minor"),
                rs.getTimestamp("early_bird_ends_at") == null ? null
                    : rs.getTimestamp("early_bird_ends_at").toInstant(),
                rs.getInt("early_bird_discount_bps"),
                (Integer) rs.getObject("group_size_threshold"),
                rs.getInt("group_discount_bps")))
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
                          AND ((h.state = 'ACTIVE' AND h.expires_at > :now)
                            OR h.state = 'PAYMENT_PENDING')
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
                      AND ((state = 'ACTIVE' AND expires_at > :now)
                        OR state = 'PAYMENT_PENDING')
                )
                """)
            .param("accountId", accountId).param("eventId", eventId)
            .param("now", Timestamp.from(now)).query(Boolean.class).single();
    }

    List<SeatForHold> lockSeats(UUID eventId, List<UUID> seatIds) {
        return jdbcClient.sql("""
                SELECT es.id, es.inventory_state, pt.price_minor, pt.currency
                FROM event_seat es JOIN price_tier pt ON pt.id = es.price_tier_id
                WHERE es.event_id = :eventId AND es.id IN (:seatIds)
                ORDER BY id
                FOR UPDATE
                """)
            .param("eventId", eventId).param("seatIds", seatIds)
            .query((rs, row) -> new SeatForHold(
                rs.getObject("id", UUID.class), rs.getString("inventory_state"),
                rs.getLong("price_minor"), rs.getString("currency").strip()))
            .list();
    }

    boolean anyActivelyHeld(List<UUID> seatIds, Instant now) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM seat_hold_item i JOIN seat_hold h ON h.id = i.hold_id
                    WHERE i.event_seat_id IN (:seatIds)
                      AND ((h.state = 'ACTIVE' AND h.expires_at > :now)
                        OR h.state = 'PAYMENT_PENDING')
                )
                """)
            .param("seatIds", seatIds).param("now", Timestamp.from(now))
            .query(Boolean.class).single();
    }

    Optional<PromoForHold> lockPromo(String code, UUID eventId, UUID accountId, Instant now) {
        return jdbcClient.sql("""
                SELECT p.id, p.discount_type, p.discount_value, p.exclusive,
                       p.global_limit, p.per_customer_limit, p.redeemed_count,
                       (SELECT count(*) FROM promo_redemption r
                        WHERE r.promo_id = p.id AND r.account_id = :accountId
                          AND r.state IN ('RESERVED', 'CONSUMED')) AS customer_uses
                FROM promo_code p
                WHERE p.code = :code AND p.active
                  AND (p.event_id IS NULL OR p.event_id = :eventId)
                  AND p.valid_from <= :now AND :now < p.valid_until
                FOR UPDATE
                """)
            .param("code", code).param("eventId", eventId).param("accountId", accountId)
            .param("now", Timestamp.from(now))
            .query((rs, row) -> new PromoForHold(rs.getObject("id", UUID.class),
                PricingPolicy.PromoType.valueOf(rs.getString("discount_type")),
                rs.getLong("discount_value"), rs.getBoolean("exclusive"),
                (Integer) rs.getObject("global_limit"), (Integer) rs.getObject("per_customer_limit"),
                rs.getInt("redeemed_count"), rs.getInt("customer_uses")))
            .optional();
    }

    UUID insertHold(UUID eventId, UUID accountId, UUID promoId, Instant now, Instant expiresAt,
                    PricingPolicy.Quote quote) {
        return jdbcClient.sql("""
                INSERT INTO seat_hold (event_id, account_id, promo_id, created_at, expires_at,
                    currency, subtotal_minor, discount_minor, service_fee_minor, total_minor)
                VALUES (:eventId, :accountId, :promoId, :now, :expiresAt, :currency,
                    :subtotal, :discount, :fee, :total)
                RETURNING id
                """)
            .param("eventId", eventId).param("accountId", accountId)
            .param("promoId", promoId).param("currency", quote.currency())
            .param("subtotal", quote.subtotalMinor()).param("discount", quote.discountMinor())
            .param("fee", quote.serviceFeeMinor()).param("total", quote.totalMinor())
            .param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
            .query(UUID.class).single();
    }

    void insertItems(UUID holdId, UUID eventId, PricingPolicy.Quote quote,
                     Instant now, Instant expiresAt) {
        for (PricingPolicy.Line line : quote.lines()) {
            jdbcClient.sql("""
                    INSERT INTO seat_hold_item (
                        hold_id, event_id, event_seat_id, claim_started_at, claim_ends_at,
                        base_price_minor, early_discount_minor, group_discount_minor,
                        promo_discount_minor, service_fee_minor, final_total_minor, currency
                    ) VALUES (:holdId, :eventId, :seatId, :now, :expiresAt, :base,
                        :early, :groupDiscount, :promo, :fee, :total, :currency)
                    """)
                .param("holdId", holdId).param("eventId", eventId).param("seatId", line.seatId())
                .param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
                .param("base", line.basePriceMinor()).param("early", line.earlyDiscountMinor())
                .param("groupDiscount", line.groupDiscountMinor())
                .param("promo", line.promoDiscountMinor()).param("fee", line.serviceFeeMinor())
                .param("total", line.finalTotalMinor()).param("currency", quote.currency())
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

    record EventForHold(UUID id, String state, Instant salesStartAt, Instant salesEndAt,
                        String currency, long serviceFeeMinor, Instant earlyBirdEndsAt,
                        int earlyBirdDiscountBps, Integer groupSizeThreshold,
                        int groupDiscountBps) {}
    record SeatForHold(UUID id, String inventoryState, long priceMinor, String currency) {}
    record PromoForHold(UUID id, PricingPolicy.PromoType type, long value, boolean exclusive,
                        Integer globalLimit, Integer perCustomerLimit, int redeemedCount,
                        int customerUses) {}
}
