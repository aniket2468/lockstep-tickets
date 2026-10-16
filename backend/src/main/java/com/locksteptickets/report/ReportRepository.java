package com.locksteptickets.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReportRepository {
    private final JdbcClient jdbc;
    ReportRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    Optional<SalesRow> sales(UUID eventId) {
        return jdbc.sql("""
                SELECT e.id,e.currency,
                  (SELECT count(*) FROM booking b WHERE b.event_id=e.id
                    AND EXISTS(SELECT 1 FROM ticket t WHERE t.booking_id=b.id)) AS booking_count,
                  (SELECT count(*) FROM ticket t WHERE t.event_id=e.id) AS ticket_count,
                  (SELECT count(*) FROM ticket t WHERE t.event_id=e.id
                    AND t.state IN ('CONFIRMED','CANCELLATION_PENDING')) AS active_ticket_count,
                  (SELECT count(*) FROM ticket t WHERE t.event_id=e.id AND t.state='REFUNDED') AS refunded_ticket_count,
                  coalesce((SELECT sum(t.charged_total_minor) FROM ticket t WHERE t.event_id=e.id),0) AS gross,
                  coalesce((SELECT sum(r.amount_minor) FROM refund r JOIN booking b ON b.id=r.booking_id
                    WHERE b.event_id=e.id AND r.state='SUCCEEDED'),0) AS refunded,
                  coalesce((SELECT sum(r.amount_minor) FROM refund r JOIN booking b ON b.id=r.booking_id
                    WHERE b.event_id=e.id AND r.state IN ('REQUESTED','UNKNOWN')),0) AS pending
                FROM event e WHERE e.id=:event
                """).param("event",eventId).query((rs,n)->new SalesRow(rs.getObject("id",UUID.class),
                    rs.getString("currency").strip(),rs.getLong("booking_count"),rs.getLong("ticket_count"),
                    rs.getLong("active_ticket_count"),rs.getLong("refunded_ticket_count"),
                    rs.getLong("gross"),rs.getLong("refunded"),rs.getLong("pending"))).optional();
    }

    Optional<OccupancyRow> occupancy(UUID eventId,Instant now) {
        return jdbc.sql("""
                WITH classified AS (
                  SELECT es.id,CASE
                    WHEN es.inventory_state='BLOCKED' THEN 'BLOCKED'
                    WHEN EXISTS(SELECT 1 FROM ticket t WHERE t.event_seat_id=es.id
                      AND t.state IN ('CONFIRMED','CANCELLATION_PENDING')) THEN 'SOLD'
                    WHEN EXISTS(SELECT 1 FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
                      WHERE i.event_seat_id=es.id AND h.state='PAYMENT_PENDING') THEN 'PAYMENT_PENDING'
                    WHEN EXISTS(SELECT 1 FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
                      WHERE i.event_seat_id=es.id AND h.state='WAITLIST_OFFER' AND h.expires_at>:now)
                      THEN 'WAITLIST_OFFERED'
                    WHEN EXISTS(SELECT 1 FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
                      WHERE i.event_seat_id=es.id AND h.state='ACTIVE' AND h.expires_at>:now)
                      THEN 'HELD'
                    ELSE 'UNALLOCATED' END AS bucket
                  FROM event_seat es WHERE es.event_id=:event
                )
                SELECT e.id,e.state,e.sales_start_at,e.sales_end_at,
                  count(c.id) AS total,
                  count(*) FILTER(WHERE c.bucket='BLOCKED') AS blocked,
                  count(*) FILTER(WHERE c.bucket='SOLD') AS sold,
                  count(*) FILTER(WHERE c.bucket='PAYMENT_PENDING') AS payment_pending,
                  count(*) FILTER(WHERE c.bucket='WAITLIST_OFFERED') AS waitlist_offered,
                  count(*) FILTER(WHERE c.bucket='HELD') AS held,
                  count(*) FILTER(WHERE c.bucket='UNALLOCATED') AS unallocated
                FROM event e LEFT JOIN classified c ON true WHERE e.id=:event
                GROUP BY e.id
                """).param("event",eventId).param("now",Timestamp.from(now))
            .query((rs,n)->new OccupancyRow(rs.getObject("id",UUID.class),rs.getString("state"),
                rs.getTimestamp("sales_start_at").toInstant(),rs.getTimestamp("sales_end_at").toInstant(),
                rs.getLong("total"),rs.getLong("blocked"),rs.getLong("sold"),
                rs.getLong("payment_pending"),rs.getLong("waitlist_offered"),rs.getLong("held"),
                rs.getLong("unallocated"))).optional();
    }

    record SalesRow(UUID eventId,String currency,long bookingCount,long ticketCount,long activeTicketCount,
                    long refundedTicketCount,long gross,long refunded,long pending){}
    record OccupancyRow(UUID eventId,String eventState,Instant salesStartAt,Instant salesEndAt,long total,
                        long blocked,long sold,long paymentPending,long waitlistOffered,long held,long unallocated){}
}
