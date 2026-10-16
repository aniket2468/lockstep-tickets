package com.locksteptickets.checkout;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CheckoutRepository {

    private static final Instant PAYMENT_CLAIM_END = Instant.parse("9999-12-31T23:59:59Z");
    private final JdbcClient jdbc;

    CheckoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<HoldRow> findOwnedHold(UUID holdId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, event_id, account_id, state, expires_at, promo_id, currency,
                       subtotal_minor, discount_minor, service_fee_minor, total_minor
                FROM seat_hold WHERE id = :id AND account_id = :accountId
                """).param("id", holdId).param("accountId", accountId)
            .query((rs, row) -> new HoldRow(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("state"), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("promo_id", UUID.class), rs.getString("currency") == null ? null
                    : rs.getString("currency").strip(), (Long) rs.getObject("subtotal_minor"),
                (Long) rs.getObject("discount_minor"), (Long) rs.getObject("service_fee_minor"),
                (Long) rs.getObject("total_minor"))).optional();
    }

    Optional<BookingRow> findByKey(UUID accountId, String key) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.account_id = :accountId AND b.checkout_key = :key")
            .param("accountId", accountId).param("key", key).query(this::mapBooking).optional();
    }

    Optional<BookingRow> findOwnedBooking(UUID bookingId, UUID accountId) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.id = :id AND b.account_id = :accountId")
            .param("id", bookingId).param("accountId", accountId).query(this::mapBooking).optional();
    }

    Optional<BookingRow> findBooking(UUID bookingId) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.id = :id")
            .param("id", bookingId).query(this::mapBooking).optional();
    }

    List<UUID> findReconciliationCandidates(Instant cutoff, int limit) {
        return jdbc.sql("""
                SELECT b.id
                FROM booking b
                JOIN payment_attempt p ON p.booking_id = b.id
                WHERE b.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN')
                  AND p.updated_at <= :cutoff
                ORDER BY p.updated_at, b.id
                LIMIT :limit
                """)
            .param("cutoff", Timestamp.from(cutoff))
            .param("limit", limit)
            .query(UUID.class).list();
    }

    Optional<UUID> accountIdForBooking(UUID bookingId) {
        return jdbc.sql("SELECT account_id FROM booking WHERE id = :id")
            .param("id", bookingId).query(UUID.class).optional();
    }

    String lockEvent(UUID eventId) {
        return jdbc.sql("SELECT state FROM event WHERE id = :id FOR UPDATE")
            .param("id", eventId).query(String.class).single();
    }

    void lockLimit(UUID accountId, UUID eventId) {
        jdbc.sql("""
                SELECT account_id FROM customer_event_limit
                WHERE account_id = :accountId AND event_id = :eventId FOR UPDATE
                """).param("accountId", accountId).param("eventId", eventId)
            .query(UUID.class).single();
    }

    List<HoldItem> lockItems(UUID holdId) {
        return jdbc.sql("""
                SELECT i.event_seat_id, i.base_price_minor, i.early_discount_minor,
                       i.group_discount_minor, i.promo_discount_minor, i.service_fee_minor,
                       i.final_total_minor, i.currency
                FROM seat_hold_item i JOIN event_seat s ON s.id = i.event_seat_id
                WHERE i.hold_id = :holdId ORDER BY i.event_seat_id FOR UPDATE OF s
                """).param("holdId", holdId).query((rs, row) -> new HoldItem(
                    rs.getObject("event_seat_id", UUID.class),
                    (Long) rs.getObject("base_price_minor"),
                    (Long) rs.getObject("early_discount_minor"),
                    (Long) rs.getObject("group_discount_minor"),
                    (Long) rs.getObject("promo_discount_minor"),
                    (Long) rs.getObject("service_fee_minor"),
                    (Long) rs.getObject("final_total_minor"),
                    rs.getString("currency") == null ? null : rs.getString("currency").strip()))
                .list();
    }

    void lockPromo(UUID promoId) {
        jdbc.sql("SELECT id FROM promo_code WHERE id = :id FOR UPDATE")
            .param("id", promoId).query(UUID.class).single();
    }

    boolean promoHasCapacity(UUID promoId, UUID accountId) {
        return jdbc.sql("""
                SELECT (p.global_limit IS NULL OR
                           (SELECT count(*) FROM promo_redemption r WHERE r.promo_id = p.id
                            AND r.state IN ('RESERVED','CONSUMED')) < p.global_limit)
                   AND (p.per_customer_limit IS NULL OR
                           (SELECT count(*) FROM promo_redemption r WHERE r.promo_id = p.id
                            AND r.account_id = :accountId
                            AND r.state IN ('RESERVED','CONSUMED')) < p.per_customer_limit)
                FROM promo_code p WHERE p.id = :promoId
                """).param("promoId", promoId).param("accountId", accountId)
            .query(Boolean.class).single();
    }

    HoldRow lockHold(UUID holdId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, event_id, account_id, state, expires_at, promo_id, currency,
                       subtotal_minor, discount_minor, service_fee_minor, total_minor
                FROM seat_hold WHERE id = :id AND account_id = :accountId FOR UPDATE
                """).param("id", holdId).param("accountId", accountId)
            .query((rs, row) -> new HoldRow(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("state"), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("promo_id", UUID.class), rs.getString("currency") == null ? null
                    : rs.getString("currency").strip(), (Long) rs.getObject("subtotal_minor"),
                (Long) rs.getObject("discount_minor"), (Long) rs.getObject("service_fee_minor"),
                (Long) rs.getObject("total_minor"))).single();
    }

    UUID createBooking(HoldRow hold, String key, byte[] fingerprint, Instant now) {
        String reference = "LT-" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 16).toUpperCase();
        return jdbc.sql("""
                INSERT INTO booking (reference, account_id, event_id, hold_id, state, currency,
                    subtotal_minor, discount_minor, service_fee_minor, total_minor,
                    checkout_key, request_fingerprint, created_at, updated_at)
                VALUES (:reference, :accountId, :eventId, :holdId, 'PAYMENT_PENDING', :currency,
                    :subtotal, :discount, :fee, :total, :key, :fingerprint, :now, :now)
                RETURNING id
                """).param("reference", reference).param("accountId", hold.accountId())
            .param("eventId", hold.eventId()).param("holdId", hold.id())
            .param("currency", hold.currency()).param("subtotal", hold.subtotalMinor())
            .param("discount", hold.discountMinor()).param("fee", hold.serviceFeeMinor())
            .param("total", hold.totalMinor()).param("key", key).param("fingerprint", fingerprint)
            .param("now", Timestamp.from(now)).query(UUID.class).single();
    }

    void createAttempt(UUID bookingId, MockPaymentGateway.RequestedOutcome requested,
                       MockPaymentGateway.LookupOutcome lookup, long amount, String currency,
                       Instant now) {
        jdbc.sql("""
                INSERT INTO payment_attempt (booking_id, state, requested_outcome, lookup_outcome,
                    amount_minor, currency, provider_reference, created_at, updated_at)
                VALUES (:bookingId, 'INITIATED', :requested, :lookup, :amount, :currency,
                    :reference, :now, :now)
                """).param("bookingId", bookingId).param("requested", requested.name())
            .param("lookup", lookup.name()).param("amount", amount).param("currency", currency)
            .param("reference", "pay_" + UUID.randomUUID()).param("now", Timestamp.from(now)).update();
    }

    void reservePromo(UUID promoId, UUID accountId, UUID bookingId, Instant now) {
        jdbc.sql("""
                INSERT INTO promo_redemption (promo_id, account_id, booking_id, state, created_at)
                VALUES (:promoId, :accountId, :bookingId, 'RESERVED', :now)
                """).param("promoId", promoId).param("accountId", accountId)
            .param("bookingId", bookingId).param("now", Timestamp.from(now)).update();
    }

    void markPaymentPending(UUID holdId) {
        jdbc.sql("UPDATE seat_hold SET state = 'PAYMENT_PENDING', version = version + 1 WHERE id = :id")
            .param("id", holdId).update();
        jdbc.sql("UPDATE seat_hold_item SET claim_ends_at = :ends WHERE hold_id = :id")
            .param("ends", Timestamp.from(PAYMENT_CLAIM_END)).param("id", holdId).update();
    }

    void recordObserved(UUID bookingId, MockPaymentGateway.ObservedOutcome outcome, Instant now) {
        String state = switch (outcome) {
            case SUCCESS -> "SUCCEEDED";
            case DECLINE -> "DECLINED";
            case TIMEOUT, UNKNOWN -> "UNKNOWN";
        };
        jdbc.sql("""
                UPDATE payment_attempt SET state = :state, observed_outcome = :outcome,
                    updated_at = :now
                WHERE booking_id = :bookingId AND state = 'INITIATED'
                """).param("state", state).param("outcome", outcome.name())
            .param("now", Timestamp.from(now)).param("bookingId", bookingId).update();
    }

    void recordLookup(UUID bookingId, MockPaymentGateway.LookupOutcome outcome, Instant now) {
        String state = switch (outcome) {
            case SUCCESS -> "SUCCEEDED";
            case DECLINE -> "DECLINED";
            case UNKNOWN -> "UNKNOWN";
        };
        jdbc.sql("""
                UPDATE payment_attempt SET state = :state, observed_outcome = :outcome,
                    updated_at = :now
                WHERE booking_id = :bookingId AND state IN ('INITIATED', 'UNKNOWN')
                """).param("state", state).param("outcome", outcome.name())
            .param("now", Timestamp.from(now)).param("bookingId", bookingId).update();
    }

    void confirm(BookingRow booking, List<HoldItem> items, Instant now) {
        for (HoldItem item : items) {
            jdbc.sql("""
                    INSERT INTO ticket (booking_id, event_id, event_seat_id, base_price_minor,
                        early_discount_minor, group_discount_minor, promo_discount_minor,
                        service_fee_minor, charged_total_minor, currency, created_at)
                    VALUES (:bookingId, :eventId, :seatId, :base, :early, :groupDiscount,
                        :promo, :fee, :total, :currency, :now)
                    """).param("bookingId", booking.id()).param("eventId", booking.eventId())
                .param("seatId", item.seatId()).param("base", item.basePriceMinor())
                .param("early", item.earlyDiscountMinor()).param("groupDiscount", item.groupDiscountMinor())
                .param("promo", item.promoDiscountMinor()).param("fee", item.serviceFeeMinor())
                .param("total", item.finalTotalMinor()).param("currency", item.currency())
                .param("now", Timestamp.from(now)).update();
        }
        jdbc.sql("UPDATE booking SET state = 'CONFIRMED', updated_at = :now, version = version + 1 WHERE id = :id")
            .param("now", Timestamp.from(now)).param("id", booking.id()).update();
        finishHold(booking.holdId(), "CONSUMED", now);
        jdbc.sql("""
                UPDATE customer_event_limit SET active_hold_seat_count = active_hold_seat_count - :size,
                    confirmed_ticket_count = confirmed_ticket_count + :size, version = version + 1
                WHERE account_id = :accountId AND event_id = :eventId
                """).param("size", items.size()).param("accountId", booking.accountId())
            .param("eventId", booking.eventId()).update();
        jdbc.sql("""
                UPDATE promo_redemption SET state = 'CONSUMED' WHERE booking_id = :bookingId
                """).param("bookingId", booking.id()).update();
        jdbc.sql("""
                UPDATE promo_code SET redeemed_count = redeemed_count + 1, version = version + 1
                WHERE id = (SELECT promo_id FROM promo_redemption WHERE booking_id = :bookingId)
                """).param("bookingId", booking.id()).update();
        jdbc.sql("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES ('BOOKING', :id, 'BOOKING_CONFIRMED', :payload, :now)
                """).param("id", booking.id()).param("payload", "{\"bookingId\":\"" + booking.id() + "\"}")
            .param("now", Timestamp.from(now)).update();
    }

    void fail(BookingRow booking, int size, Instant now) {
        jdbc.sql("UPDATE booking SET state = 'PAYMENT_FAILED', updated_at = :now, version = version + 1 WHERE id = :id")
            .param("now", Timestamp.from(now)).param("id", booking.id()).update();
        finishHold(booking.holdId(), "RELEASED", now);
        jdbc.sql("""
                UPDATE customer_event_limit SET active_hold_seat_count = active_hold_seat_count - :size,
                    version = version + 1 WHERE account_id = :accountId AND event_id = :eventId
                """).param("size", size).param("accountId", booking.accountId())
            .param("eventId", booking.eventId()).update();
        jdbc.sql("UPDATE promo_redemption SET state = 'RESTORED' WHERE booking_id = :id")
            .param("id", booking.id()).update();
    }

    void markUnknown(UUID bookingId, Instant now) {
        jdbc.sql("UPDATE booking SET state = 'PAYMENT_UNKNOWN', updated_at = :now, version = version + 1 WHERE id = :id")
            .param("now", Timestamp.from(now)).param("id", bookingId).update();
    }

    private void finishHold(UUID holdId, String state, Instant now) {
        jdbc.sql("UPDATE seat_hold SET state = :state, ended_at = :now, version = version + 1 WHERE id = :id")
            .param("state", state).param("now", Timestamp.from(now)).param("id", holdId).update();
        jdbc.sql("UPDATE seat_hold_item SET claim_ends_at = :now WHERE hold_id = :id")
            .param("now", Timestamp.from(now)).param("id", holdId).update();
    }

    BookingRow lockBooking(UUID bookingId) {
        return jdbc.sql(BOOKING_SELECT + " WHERE b.id = :id FOR UPDATE OF b")
            .param("id", bookingId).query(this::mapBooking).single();
    }

    MockPaymentGateway.LookupOutcome lookupOutcome(UUID bookingId) {
        String value = jdbc.sql("SELECT lookup_outcome FROM payment_attempt WHERE booking_id = :id")
            .param("id", bookingId).query(String.class).single();
        return MockPaymentGateway.LookupOutcome.valueOf(value);
    }

    String paymentState(UUID bookingId) {
        return jdbc.sql("SELECT state FROM payment_attempt WHERE booking_id = :id")
            .param("id", bookingId).query(String.class).single();
    }

    private BookingRow mapBooking(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BookingRow(rs.getObject("id", UUID.class), rs.getString("reference"),
            rs.getObject("account_id", UUID.class), rs.getObject("event_id", UUID.class),
            rs.getObject("hold_id", UUID.class), rs.getString("state"), rs.getString("currency").strip(),
            rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
            rs.getLong("service_fee_minor"), rs.getLong("total_minor"),
            rs.getString("checkout_key"), rs.getBytes("request_fingerprint"));
    }

    private static final String BOOKING_SELECT = """
        SELECT b.id, b.reference, b.account_id, b.event_id, b.hold_id, b.state, b.currency,
               b.subtotal_minor, b.discount_minor, b.service_fee_minor, b.total_minor,
               b.checkout_key, b.request_fingerprint FROM booking b
        """;

    record HoldRow(UUID id, UUID eventId, UUID accountId, String state, Instant expiresAt,
                   UUID promoId, String currency, Long subtotalMinor, Long discountMinor,
                   Long serviceFeeMinor, Long totalMinor) {}
    record HoldItem(UUID seatId, Long basePriceMinor, Long earlyDiscountMinor,
                    Long groupDiscountMinor, Long promoDiscountMinor, Long serviceFeeMinor,
                    Long finalTotalMinor, String currency) {}
    record BookingRow(UUID id, String reference, UUID accountId, UUID eventId, UUID holdId,
                      String state, String currency, long subtotalMinor, long discountMinor,
                      long serviceFeeMinor, long totalMinor, String checkoutKey,
                      byte[] fingerprint) {}
}
