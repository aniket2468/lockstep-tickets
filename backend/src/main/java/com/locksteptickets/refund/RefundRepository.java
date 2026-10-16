package com.locksteptickets.refund;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.locksteptickets.checkout.MockPaymentGateway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefundRepository {
    private final JdbcClient jdbc;

    RefundRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<Booking> findOwnedBooking(UUID id, UUID accountId) {
        return jdbc.sql("""
                SELECT b.id,b.account_id,b.event_id,b.state,b.currency,e.starts_at,e.time_zone,
                       (SELECT er.refund_window_ends_at FROM event_reschedule er
                        WHERE er.event_id=b.event_id
                        ORDER BY er.announced_at DESC,er.id DESC LIMIT 1) AS refund_window_ends_at
                FROM booking b JOIN event e ON e.id=b.event_id
                WHERE b.id=:id AND b.account_id=:accountId
                """).param("id", id).param("accountId", accountId)
            .query((rs,n) -> new Booking(rs.getObject("id",UUID.class),
                rs.getObject("account_id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getString("state"),rs.getString("currency").strip(),
                rs.getTimestamp("starts_at").toInstant(),rs.getString("time_zone"),
                instant(rs,"refund_window_ends_at"))).optional();
    }

    List<BookingSummary> listBookings(UUID accountId) {
        return jdbc.sql("""
                SELECT b.id,b.reference,b.state,b.currency,b.total_minor,e.name,e.starts_at
                FROM booking b JOIN event e ON e.id=b.event_id
                WHERE b.account_id=:accountId
                ORDER BY b.created_at DESC,b.id DESC
                """).param("accountId",accountId).query((rs,n)->new BookingSummary(
                    rs.getObject("id",UUID.class),rs.getString("reference"),rs.getString("state"),
                    rs.getString("currency").strip(),rs.getLong("total_minor"),
                    rs.getString("name"),rs.getTimestamp("starts_at").toInstant())).list();
    }

    List<Ticket> tickets(UUID bookingId) {
        return jdbc.sql("""
                SELECT t.id,t.event_seat_id,t.state,t.charged_total_minor,t.service_fee_minor,
                       t.currency,s.section_name,s.row_label,s.seat_number
                FROM ticket t JOIN event_seat s ON s.id=t.event_seat_id
                WHERE t.booking_id=:id ORDER BY t.id
                """).param("id",bookingId).query(this::mapTicket).list();
    }

    List<Ticket> selectedTickets(UUID bookingId,List<UUID> ids) {
        return jdbc.sql("""
                SELECT t.id,t.event_seat_id,t.state,t.charged_total_minor,t.service_fee_minor,
                       t.currency,s.section_name,s.row_label,s.seat_number
                FROM ticket t JOIN event_seat s ON s.id=t.event_seat_id
                WHERE t.booking_id=:bookingId AND t.id IN (:ids) ORDER BY t.id
                """).param("bookingId",bookingId).param("ids",ids).query(this::mapTicket).list();
    }

    Optional<Refund> findByKey(UUID accountId,String key) {
        return jdbc.sql(REFUND_SELECT+" WHERE r.account_id=:accountId AND r.refund_key=:key")
            .param("accountId",accountId).param("key",key).query(this::mapRefund).optional();
    }

    Optional<Refund> findOwnedRefund(UUID id,UUID accountId) {
        return jdbc.sql(REFUND_SELECT+" WHERE r.id=:id AND r.account_id=:accountId")
            .param("id",id).param("accountId",accountId).query(this::mapRefund).optional();
    }
    Optional<UUID> accountIdForRefund(UUID id) { return jdbc.sql("SELECT account_id FROM refund WHERE id=:id")
        .param("id",id).query(UUID.class).optional(); }

    void lockEvent(UUID id) { jdbc.sql("SELECT id FROM event WHERE id=:id FOR UPDATE")
        .param("id",id).query(UUID.class).single(); }
    void lockLimit(UUID accountId,UUID eventId) { jdbc.sql("""
            SELECT account_id FROM customer_event_limit
            WHERE account_id=:a AND event_id=:e FOR UPDATE
            """).param("a",accountId).param("e",eventId).query(UUID.class).single(); }
    void lockSeats(List<UUID> ids) { jdbc.sql("SELECT id FROM event_seat WHERE id IN (:ids) ORDER BY id FOR UPDATE")
        .param("ids",ids).query(UUID.class).list(); }
    Booking lockBooking(UUID id,UUID accountId) { return jdbc.sql("""
            SELECT b.id,b.account_id,b.event_id,b.state,b.currency,e.starts_at,e.time_zone,
                   (SELECT er.refund_window_ends_at FROM event_reschedule er
                    WHERE er.event_id=b.event_id
                    ORDER BY er.announced_at DESC,er.id DESC LIMIT 1) AS refund_window_ends_at
            FROM booking b JOIN event e ON e.id=b.event_id
            WHERE b.id=:id AND b.account_id=:a FOR UPDATE OF b
            """).param("id",id).param("a",accountId).query((rs,n)->new Booking(
                rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),
                rs.getObject("event_id",UUID.class),rs.getString("state"),
                rs.getString("currency").strip(),rs.getTimestamp("starts_at").toInstant(),
                rs.getString("time_zone"),instant(rs,"refund_window_ends_at"))).single(); }
    List<Ticket> lockTickets(UUID bookingId,List<UUID> ids) { return jdbc.sql("""
            SELECT t.id,t.event_seat_id,t.state,t.charged_total_minor,t.service_fee_minor,
                   t.currency,s.section_name,s.row_label,s.seat_number
            FROM ticket t JOIN event_seat s ON s.id=t.event_seat_id
            WHERE t.booking_id=:bookingId AND t.id IN (:ids) ORDER BY t.id FOR UPDATE OF t
            """).param("bookingId",bookingId).param("ids",ids).query(this::mapTicket).list(); }

    UUID createRefund(Booking b,String key,byte[] fingerprint,String kind,int bps,long amount,
                      long serviceFeeRefund,Instant now) {
        return jdbc.sql("""
                INSERT INTO refund(booking_id,account_id,refund_key,request_fingerprint,
                    kind,percentage_bps,amount_minor,service_fee_refund_minor,currency,created_at,updated_at)
                VALUES(:b,:a,:key,:fp,:kind,:bps,:amount,:fee,:currency,:now,:now) RETURNING id
                """).param("b",b.id()).param("a",b.accountId()).param("key",key).param("fp",fingerprint)
            .param("kind",kind).param("bps",bps).param("amount",amount).param("fee",serviceFeeRefund)
            .param("currency",b.currency())
            .param("now",Timestamp.from(now)).query(UUID.class).single();
    }

    void addItems(UUID refundId,List<Ticket> tickets,int bps,boolean includeFee) {
        for (Ticket t:tickets) {
            long refundable=t.chargedTotalMinor()-t.serviceFeeMinor();
            long fee=includeFee?t.serviceFeeMinor():0;
            long amount=Math.addExact(Math.multiplyExact(refundable,bps)/10_000,fee);
            jdbc.sql("""
                    INSERT INTO refund_item(refund_id,ticket_id,charged_refundable_minor,
                        service_fee_refund_minor,refund_amount_minor)
                    VALUES(:r,:t,:charged,:fee,:amount)
                    """).param("r",refundId).param("t",t.id()).param("charged",refundable)
                .param("fee",fee).param("amount",amount).update();
        }
    }

    void markTicketsPending(List<UUID> ids) { jdbc.sql("UPDATE ticket SET state='CANCELLATION_PENDING' WHERE id IN (:ids)")
        .param("ids",ids).update(); }

    int createAttempt(UUID refundId,MockPaymentGateway.RequestedOutcome requested,
                      MockPaymentGateway.LookupOutcome lookup,Instant now) {
        int number=jdbc.sql("SELECT coalesce(max(attempt_number),0)+1 FROM refund_attempt WHERE refund_id=:id")
            .param("id",refundId).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO refund_attempt(refund_id,attempt_number,requested_outcome,lookup_outcome,
                    provider_reference,created_at,updated_at)
                VALUES(:id,:number,:requested,:lookup,:reference,:now,:now)
                """).param("id",refundId).param("number",number).param("requested",requested.name())
            .param("lookup",lookup.name()).param("reference","refund_"+UUID.randomUUID())
            .param("now",Timestamp.from(now)).update();
        return number;
    }

    void recordObserved(UUID refundId,int number,MockPaymentGateway.ObservedOutcome outcome,Instant now) {
        String state=switch(outcome){case SUCCESS->"SUCCEEDED";case DECLINE->"DECLINED";case TIMEOUT,UNKNOWN->"UNKNOWN";};
        jdbc.sql("""
                UPDATE refund_attempt SET state=:state,observed_outcome=:outcome,updated_at=:now
                WHERE refund_id=:id AND attempt_number=:number AND state='INITIATED'
                """).param("state",state).param("outcome",outcome.name()).param("now",Timestamp.from(now))
            .param("id",refundId).param("number",number).update();
    }

    void recordLookup(UUID refundId,int number,MockPaymentGateway.LookupOutcome outcome,Instant now) {
        String state=switch(outcome){case SUCCESS->"SUCCEEDED";case DECLINE->"DECLINED";case UNKNOWN->"UNKNOWN";};
        jdbc.sql("""
                UPDATE refund_attempt SET state=:state,observed_outcome=:outcome,updated_at=:now
                WHERE refund_id=:id AND attempt_number=:number AND state IN ('INITIATED','UNKNOWN')
                """).param("state",state).param("outcome",outcome.name()).param("now",Timestamp.from(now))
            .param("id",refundId).param("number",number).update();
    }

    Attempt latestAttempt(UUID refundId) { return jdbc.sql("""
            SELECT attempt_number,state,lookup_outcome FROM refund_attempt
            WHERE refund_id=:id ORDER BY attempt_number DESC LIMIT 1
            """).param("id",refundId).query((rs,n)->new Attempt(rs.getInt("attempt_number"),
                rs.getString("state"),MockPaymentGateway.LookupOutcome.valueOf(rs.getString("lookup_outcome")))).single(); }

    Refund lockRefund(UUID id) { return jdbc.sql(REFUND_SELECT+" WHERE r.id=:id FOR UPDATE OF r")
        .param("id",id).query(this::mapRefund).single(); }
    List<Ticket> refundTickets(UUID refundId) { return jdbc.sql("""
            SELECT t.id,t.event_seat_id,t.state,t.charged_total_minor,t.service_fee_minor,
                   t.currency,s.section_name,s.row_label,s.seat_number
            FROM refund_item i JOIN ticket t ON t.id=i.ticket_id JOIN event_seat s ON s.id=t.event_seat_id
            WHERE i.refund_id=:id ORDER BY t.id
            """).param("id",refundId).query(this::mapTicket).list(); }

    void setRefundState(UUID id,String state,Instant now) { jdbc.sql("""
            UPDATE refund SET state=:state,updated_at=:now,version=version+1 WHERE id=:id
            """).param("state",state).param("now",Timestamp.from(now)).param("id",id).update(); }

    void succeed(Refund refund,List<Ticket> tickets,Instant now) {
        long activeCount=tickets.stream().filter(t->!"REFUNDED".equals(t.state())).count();
        jdbc.sql("UPDATE ticket SET state='REFUNDED' WHERE id IN (:ids)")
            .param("ids",tickets.stream().map(Ticket::id).toList()).update();
        setRefundState(refund.id(),"SUCCEEDED",now);
        jdbc.sql("""
                UPDATE customer_event_limit SET confirmed_ticket_count=confirmed_ticket_count-:count,
                    version=version+1 WHERE account_id=:a AND event_id=:e
                """).param("count",activeCount).param("a",refund.accountId()).param("e",refund.eventId()).update();
        boolean active=jdbc.sql("SELECT EXISTS(SELECT 1 FROM ticket WHERE booking_id=:id AND state<>'REFUNDED')")
            .param("id",refund.bookingId()).query(Boolean.class).single();
        jdbc.sql("UPDATE booking SET state=:state,updated_at=:now,version=version+1 WHERE id=:id")
            .param("state",active?"PARTIALLY_REFUNDED":"REFUNDED").param("now",Timestamp.from(now))
            .param("id",refund.bookingId()).update();
        jdbc.sql("""
                INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload,created_at)
                VALUES('REFUND',:id,'REFUND_SUCCEEDED',:payload,:now)
                """).param("id",refund.id()).param("payload","{\"refundId\":\""+refund.id()+"\"}")
            .param("now",Timestamp.from(now)).update();
    }

    private Ticket mapTicket(java.sql.ResultSet rs,int n)throws java.sql.SQLException { return new Ticket(
        rs.getObject("id",UUID.class),rs.getObject("event_seat_id",UUID.class),rs.getString("state"),
        rs.getLong("charged_total_minor"),rs.getLong("service_fee_minor"),rs.getString("currency").strip(),
        rs.getString("section_name"),rs.getString("row_label"),rs.getString("seat_number")); }
    private Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
    private Refund mapRefund(java.sql.ResultSet rs,int n)throws java.sql.SQLException { return new Refund(
        rs.getObject("id",UUID.class),rs.getObject("booking_id",UUID.class),rs.getObject("account_id",UUID.class),
        rs.getObject("event_id",UUID.class),rs.getString("state"),rs.getString("refund_key"),
        rs.getBytes("request_fingerprint"),rs.getInt("percentage_bps"),rs.getLong("amount_minor"),
        rs.getString("currency").strip()); }
    private static final String REFUND_SELECT="""
        SELECT r.id,r.booking_id,r.account_id,b.event_id,r.state,r.refund_key,r.request_fingerprint,
               r.percentage_bps,r.amount_minor,r.currency FROM refund r JOIN booking b ON b.id=r.booking_id
        """;

    record Booking(UUID id,UUID accountId,UUID eventId,String state,String currency,Instant startsAt,
                   String timeZone,Instant refundWindowEndsAt){}
    record BookingSummary(UUID id,String reference,String state,String currency,long totalMinor,String eventName,Instant startsAt){}
    record Ticket(UUID id,UUID eventSeatId,String state,long chargedTotalMinor,long serviceFeeMinor,
                  String currency,String section,String row,String number){}
    record Refund(UUID id,UUID bookingId,UUID accountId,UUID eventId,String state,String key,
                  byte[] fingerprint,int percentageBps,long amountMinor,String currency){}
    record Attempt(int number,String state,MockPaymentGateway.LookupOutcome lookup){}
}
