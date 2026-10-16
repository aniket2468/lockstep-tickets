package com.locksteptickets.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.locksteptickets.checkout.MockPaymentGateway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AdminRepository {
    private final JdbcClient jdbc;
    AdminRepository(JdbcClient jdbc){this.jdbc=jdbc;}

    Optional<EventRow> lockEvent(UUID id){return jdbc.sql("""
            SELECT id,state,starts_at,sales_end_at,currency FROM event
            WHERE id=:id AND retired_at IS NULL FOR UPDATE
            """).param("id",id).query((rs,n)->new EventRow(rs.getObject("id",UUID.class),
                rs.getString("state"),rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("sales_end_at").toInstant(),rs.getString("currency").strip())).optional();}

    Optional<SeatRow> lockSeat(UUID eventId,UUID seatId){return jdbc.sql("""
            SELECT id,inventory_state FROM event_seat
            WHERE id=:seat AND event_id=:event FOR UPDATE
            """).param("seat",seatId).param("event",eventId)
        .query((rs,n)->new SeatRow(rs.getObject("id",UUID.class),rs.getString("inventory_state"))).optional();}

    void expireHolds(UUID eventId,Instant now){jdbc.sql("""
            UPDATE seat_hold SET state='EXPIRED',ended_at=expires_at,version=version+1
            WHERE event_id=:event AND state='ACTIVE' AND expires_at<=:now
            """).param("event",eventId).param("now",Timestamp.from(now)).update();}

    boolean seatClaimed(UUID seatId,Instant now){return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
              WHERE i.event_seat_id=:seat AND ((h.state='ACTIVE' AND h.expires_at>:now)
                OR h.state='PAYMENT_PENDING'))
            OR EXISTS(SELECT 1 FROM ticket WHERE event_seat_id=:seat
              AND state IN ('CONFIRMED','CANCELLATION_PENDING'))
            """).param("seat",seatId).param("now",Timestamp.from(now)).query(Boolean.class).single();}

    void setSeatState(UUID id,String state){jdbc.sql("""
            UPDATE event_seat SET inventory_state=:state,version=version+1 WHERE id=:id
            """).param("state",state).param("id",id).update();}

    boolean hasPendingPayment(UUID eventId){return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM booking WHERE event_id=:event
              AND state IN ('PAYMENT_PENDING','PAYMENT_UNKNOWN'))
            """).param("event",eventId).query(Boolean.class).single();}

    List<TicketRow> cancellableTickets(UUID eventId){return jdbc.sql("""
            SELECT t.id,t.booking_id,b.account_id,t.event_seat_id,t.charged_total_minor,
                   t.service_fee_minor,t.currency,
                   t.charged_total_minor-coalesce((SELECT sum(i.refund_amount_minor)
                     FROM refund_item i JOIN refund r ON r.id=i.refund_id
                     WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0) AS remaining_total,
                   t.service_fee_minor-coalesce((SELECT sum(i.service_fee_refund_minor)
                     FROM refund_item i JOIN refund r ON r.id=i.refund_id
                     WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0) AS remaining_fee
            FROM ticket t JOIN booking b ON b.id=t.booking_id
            WHERE t.event_id=:event AND t.charged_total_minor>coalesce((SELECT sum(i.refund_amount_minor)
              FROM refund_item i JOIN refund r ON r.id=i.refund_id
              WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0)
            ORDER BY b.account_id,t.event_seat_id,t.id
            """).param("event",eventId).query(this::ticket).list();}

    void lockLimits(UUID eventId){jdbc.sql("""
            SELECT account_id FROM customer_event_limit WHERE event_id=:event
            ORDER BY account_id FOR UPDATE
            """).param("event",eventId).query(UUID.class).list();}
    void lockSeats(List<UUID> ids){if(!ids.isEmpty())jdbc.sql("""
            SELECT id FROM event_seat WHERE id IN (:ids) ORDER BY id FOR UPDATE
            """).param("ids",ids).query(UUID.class).list();}
    void lockPromos(UUID eventId){jdbc.sql("""
            SELECT p.id FROM promo_code p WHERE p.id IN (
              SELECT r.promo_id FROM promo_redemption r JOIN booking b ON b.id=r.booking_id
              WHERE b.event_id=:event AND r.state='CONSUMED') ORDER BY p.id FOR UPDATE
            """).param("event",eventId).query(UUID.class).list();}
    void lockBookings(UUID eventId){jdbc.sql("""
            SELECT id FROM booking WHERE event_id=:event ORDER BY id FOR UPDATE
            """).param("event",eventId).query(UUID.class).list();}
    List<TicketRow> lockTickets(UUID eventId){jdbc.sql("""
            SELECT id FROM ticket WHERE event_id=:event ORDER BY id FOR UPDATE
            """).param("event",eventId).query(UUID.class).list();return cancellableTickets(eventId);}

    Optional<BookingRow> booking(UUID id){return jdbc.sql("""
            SELECT id,account_id,event_id,state,currency FROM booking WHERE id=:id
            """).param("id",id).query((rs,n)->new BookingRow(rs.getObject("id",UUID.class),
                rs.getObject("account_id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getString("state"),rs.getString("currency").strip())).optional();}
    void lockLimit(UUID accountId,UUID eventId){jdbc.sql("""
            SELECT account_id FROM customer_event_limit WHERE account_id=:account AND event_id=:event FOR UPDATE
            """).param("account",accountId).param("event",eventId).query(UUID.class).single();}
    void lockBooking(UUID id){jdbc.sql("SELECT id FROM booking WHERE id=:id FOR UPDATE")
        .param("id",id).query(UUID.class).single();}
    List<TicketRow> selectedTickets(UUID bookingId,List<UUID> ids,boolean lock){return jdbc.sql("""
            SELECT t.id,t.booking_id,b.account_id,t.event_seat_id,t.charged_total_minor,
                   t.service_fee_minor,t.currency,
                   t.charged_total_minor-coalesce((SELECT sum(i.refund_amount_minor)
                     FROM refund_item i JOIN refund r ON r.id=i.refund_id
                     WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0) AS remaining_total,
                   t.service_fee_minor-coalesce((SELECT sum(i.service_fee_refund_minor)
                     FROM refund_item i JOIN refund r ON r.id=i.refund_id
                     WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0) AS remaining_fee
            FROM ticket t JOIN booking b ON b.id=t.booking_id
            WHERE t.booking_id=:booking AND t.id IN (:ids)
              AND t.charged_total_minor>coalesce((SELECT sum(i.refund_amount_minor)
                FROM refund_item i JOIN refund r ON r.id=i.refund_id
                WHERE i.ticket_id=t.id AND r.state='SUCCEEDED'),0)
            ORDER BY t.id
            """+(lock?" FOR UPDATE OF t":"")).param("booking",bookingId).param("ids",ids)
        .query(this::ticket).list();}

    Optional<RefundRow> refundByKey(UUID accountId,String key){return jdbc.sql("""
            SELECT id,request_fingerprint,state FROM refund WHERE account_id=:account AND refund_key=:key
            """).param("account",accountId).param("key",key).query((rs,n)->new RefundRow(
                rs.getObject("id",UUID.class),rs.getBytes("request_fingerprint"),rs.getString("state"))).optional();}

    UUID createRefund(UUID bookingId,UUID accountId,String key,byte[] fingerprint,String kind,
                      long amount,long fee,String currency,List<TicketRow> tickets,
                      MockPaymentGateway.RequestedOutcome requested,
                      MockPaymentGateway.LookupOutcome lookup,Instant now){
        UUID id=jdbc.sql("""
                INSERT INTO refund(booking_id,account_id,kind,refund_key,request_fingerprint,
                  percentage_bps,amount_minor,service_fee_refund_minor,currency,created_at,updated_at)
                VALUES(:booking,:account,:kind,:key,:fp,10000,:amount,:fee,:currency,:now,:now)
                RETURNING id
                """).param("booking",bookingId).param("account",accountId).param("kind",kind)
            .param("key",key).param("fp",fingerprint).param("amount",amount).param("fee",fee)
            .param("currency",currency).param("now",Timestamp.from(now)).query(UUID.class).single();
        for(var ticket:tickets){
            jdbc.sql("""
                    INSERT INTO refund_item(refund_id,ticket_id,charged_refundable_minor,
                      service_fee_refund_minor,refund_amount_minor)
                    VALUES(:refund,:ticket,:refundable,:fee,:amount)
                    """).param("refund",id).param("ticket",ticket.id())
                .param("refundable",ticket.remainingRefundableMinor())
                .param("fee",ticket.remainingFeeMinor()).param("amount",ticket.remainingTotalMinor()).update();}
        jdbc.sql("UPDATE ticket SET state='CANCELLATION_PENDING' WHERE id IN (:ids) AND state='CONFIRMED'")
            .param("ids",tickets.stream().map(TicketRow::id).toList()).update();
        jdbc.sql("""
                INSERT INTO refund_attempt(refund_id,attempt_number,requested_outcome,lookup_outcome,
                  provider_reference,created_at,updated_at)
                VALUES(:refund,1,:requested,:lookup,:reference,:now,:now)
                """).param("refund",id).param("requested",requested.name()).param("lookup",lookup.name())
            .param("reference","refund_"+UUID.randomUUID()).param("now",Timestamp.from(now)).update();
        return id;
    }

    void restorePromos(UUID eventId){jdbc.sql("""
            WITH restored AS (
              UPDATE promo_redemption r SET state='RESTORED'
              FROM booking b WHERE b.id=r.booking_id AND b.event_id=:event AND r.state='CONSUMED'
              RETURNING r.promo_id
            ), counts AS (SELECT promo_id,count(*) AS amount FROM restored GROUP BY promo_id)
            UPDATE promo_code p SET redeemed_count=p.redeemed_count-counts.amount,version=p.version+1
            FROM counts WHERE p.id=counts.promo_id
            """).param("event",eventId).update();}
    void cancelEvent(UUID id,Instant now){jdbc.sql("""
            UPDATE event SET state='CANCELLED',updated_at=:now,version=version+1 WHERE id=:id
            """).param("id",id).param("now",Timestamp.from(now)).update();}
    void reschedule(EventRow event,Instant startsAt,UUID actor,String reason,Instant now){
        jdbc.sql("""
                UPDATE event SET starts_at=:starts,updated_at=:now,version=version+1 WHERE id=:id
                """).param("starts",Timestamp.from(startsAt)).param("now",Timestamp.from(now))
            .param("id",event.id()).update();
        jdbc.sql("""
                INSERT INTO event_reschedule(event_id,previous_starts_at,new_starts_at,announced_at,
                  refund_window_ends_at,actor_account_id,reason)
                VALUES(:event,:previous,:next,:now,:ends,:actor,:reason)
                """).param("event",event.id()).param("previous",Timestamp.from(event.startsAt()))
            .param("next",Timestamp.from(startsAt)).param("now",Timestamp.from(now))
            .param("ends",Timestamp.from(now.plus(java.time.Duration.ofDays(14))))
            .param("actor",actor).param("reason",reason).update();}

    void audit(UUID actor,String action,String type,UUID entity,String reason,String before,
               String after,Instant now){jdbc.sql("""
            INSERT INTO admin_audit(actor_account_id,action,entity_type,entity_id,reason,
              before_state,after_state,created_at)
            VALUES(:actor,:action,:type,:entity,:reason,jsonb_build_object('value',:before),
              jsonb_build_object('value',:after),:now)
            """).param("actor",actor).param("action",action).param("type",type).param("entity",entity)
        .param("reason",reason).param("before",before).param("after",after)
        .param("now",Timestamp.from(now)).update();}
    void outbox(String aggregate,UUID id,String type,Instant now){jdbc.sql("""
            INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload,created_at)
            VALUES(:aggregate,:id,:type,:payload,:now)
            """).param("aggregate",aggregate).param("id",id).param("type",type)
        .param("payload","{\"id\":\""+id+"\"}").param("now",Timestamp.from(now)).update();}
    List<AuditRow> audits(int limit,int offset){return jdbc.sql("""
            SELECT id,actor_account_id,action,entity_type,entity_id,reason,created_at
            FROM admin_audit ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset
            """).param("limit",limit).param("offset",offset).query((rs,n)->new AuditRow(
                rs.getObject("id",UUID.class),rs.getObject("actor_account_id",UUID.class),
                rs.getString("action"),rs.getString("entity_type"),rs.getObject("entity_id",UUID.class),
                rs.getString("reason"),rs.getTimestamp("created_at").toInstant())).list();}

    private TicketRow ticket(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new TicketRow(
        rs.getObject("id",UUID.class),rs.getObject("booking_id",UUID.class),
        rs.getObject("account_id",UUID.class),rs.getObject("event_seat_id",UUID.class),
        rs.getLong("charged_total_minor"),rs.getLong("service_fee_minor"),rs.getString("currency").strip(),
        rs.getLong("remaining_total")-rs.getLong("remaining_fee"),rs.getLong("remaining_fee"),
        rs.getLong("remaining_total"));}
    record EventRow(UUID id,String state,Instant startsAt,Instant salesEndAt,String currency){}
    record SeatRow(UUID id,String state){}
    record BookingRow(UUID id,UUID accountId,UUID eventId,String state,String currency){}
    record TicketRow(UUID id,UUID bookingId,UUID accountId,UUID eventSeatId,long chargedTotalMinor,
                     long serviceFeeMinor,String currency,long remainingRefundableMinor,
                     long remainingFeeMinor,long remainingTotalMinor){}
    record RefundRow(UUID id,byte[] fingerprint,String state){}
    record AuditRow(UUID id,UUID actorAccountId,String action,String entityType,UUID entityId,
                    String reason,Instant createdAt){}
}
