package com.locksteptickets.waitlist;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.locksteptickets.pricing.PricingPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WaitlistRepository {
    private final JdbcClient jdbc;

    WaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<EventRow> lockEvent(UUID id) {
        return jdbc.sql("""
                SELECT id,venue_id,state,sales_start_at,sales_end_at,currency,
                       service_fee_minor,early_bird_ends_at,early_bird_discount_bps,
                       group_size_threshold,group_discount_bps
                FROM event WHERE id=:id AND retired_at IS NULL FOR UPDATE
                """).param("id", id).query((rs,n) -> new EventRow(
                    rs.getObject("id",UUID.class),rs.getObject("venue_id",UUID.class),
                    rs.getString("state"),rs.getTimestamp("sales_start_at").toInstant(),
                    rs.getTimestamp("sales_end_at").toInstant(),rs.getString("currency").strip(),
                    rs.getLong("service_fee_minor"),instant(rs,"early_bird_ends_at"),
                    rs.getInt("early_bird_discount_bps"),(Integer)rs.getObject("group_size_threshold"),
                    rs.getInt("group_discount_bps"))).optional();
    }

    boolean sectionBelongsToVenue(UUID sectionId,UUID venueId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM section
                WHERE id=:section AND venue_id=:venue AND retired_at IS NULL)
                """).param("section",sectionId).param("venue",venueId).query(Boolean.class).single();
    }

    void expireOffers(UUID eventId,Instant now) {
        jdbc.sql("""
                UPDATE waitlist_entry SET state='EXPIRED',resolved_at=offer_expires_at,
                    updated_at=:now,version=version+1
                WHERE event_id=:event AND state='OFFERED' AND offer_expires_at<=:now
                """).param("event",eventId).param("now",Timestamp.from(now)).update();
        jdbc.sql("""
                UPDATE seat_hold SET state='EXPIRED',ended_at=expires_at,version=version+1
                WHERE event_id=:event AND state='WAITLIST_OFFER' AND expires_at<=:now
                """).param("event",eventId).param("now",Timestamp.from(now)).update();
    }

    int availableCount(UUID eventId,UUID sectionId,Instant now) {
        return jdbc.sql(AVAILABLE_SELECT.replace("SELECT es.id,pt.price_minor,pt.currency",
                "SELECT count(*)"))
            .param("event",eventId).param("section",sectionId).param("now",Timestamp.from(now))
            .query(Integer.class).single();
    }

    List<SeatRow> lockAvailableSeats(UUID eventId,UUID sectionId,int quantity,Instant now) {
        return jdbc.sql(AVAILABLE_SELECT + " ORDER BY es.display_order,es.id LIMIT :quantity FOR UPDATE OF es")
            .param("event",eventId).param("section",sectionId).param("now",Timestamp.from(now))
            .param("quantity",quantity).query((rs,n)->new SeatRow(rs.getObject("id",UUID.class),
                rs.getLong("price_minor"),rs.getString("currency").strip())).list();
    }

    UUID insertEntry(UUID eventId,UUID accountId,UUID sectionId,int quantity,Instant now) {
        return jdbc.sql("""
                INSERT INTO waitlist_entry(event_id,account_id,preferred_section_id,quantity,
                    created_at,updated_at) VALUES(:event,:account,:section,:quantity,:now,:now)
                RETURNING id
                """).param("event",eventId).param("account",accountId).param("section",sectionId)
            .param("quantity",quantity).param("now",Timestamp.from(now)).query(UUID.class).single();
    }

    List<EntryRow> candidates(UUID eventId) {
        return jdbc.sql(ENTRY_SELECT+"""
                WHERE w.event_id=:event AND w.state='WAITING'
                ORDER BY w.enqueue_sequence LIMIT 100
                """).param("event",eventId).query(this::entry).list();
    }

    Optional<EntryRow> findOwned(UUID id,UUID accountId) {
        return jdbc.sql(ENTRY_SELECT+" WHERE w.id=:id AND w.account_id=:account")
            .param("id",id).param("account",accountId).query(this::entry).optional();
    }

    EntryRow lockEntry(UUID id) {
        return jdbc.sql(ENTRY_SELECT+" WHERE w.id=:id FOR UPDATE OF w")
            .param("id",id).query(this::entry).single();
    }

    List<EntryRow> owned(UUID accountId) {
        return jdbc.sql(ENTRY_SELECT+"""
                WHERE w.account_id=:account ORDER BY w.created_at DESC,w.id DESC
                """).param("account",accountId).query(this::entry).list();
    }

    void ensureAndLockLimit(UUID accountId,UUID eventId) {
        jdbc.sql("""
                INSERT INTO customer_event_limit(account_id,event_id) VALUES(:account,:event)
                ON CONFLICT(account_id,event_id) DO NOTHING
                """).param("account",accountId).param("event",eventId).update();
        jdbc.sql("""
                SELECT account_id FROM customer_event_limit
                WHERE account_id=:account AND event_id=:event FOR UPDATE
                """).param("account",accountId).param("event",eventId).query(UUID.class).single();
    }

    int refreshAndGetTotal(UUID accountId,UUID eventId,Instant now) {
        return jdbc.sql("""
                UPDATE customer_event_limit l SET active_hold_seat_count=(
                    SELECT count(*) FROM seat_hold h JOIN seat_hold_item i ON i.hold_id=h.id
                    WHERE h.account_id=l.account_id AND h.event_id=l.event_id
                      AND ((h.state IN ('ACTIVE','WAITLIST_OFFER') AND h.expires_at>:now)
                        OR h.state='PAYMENT_PENDING')),
                    version=version+1
                WHERE account_id=:account AND event_id=:event
                RETURNING active_hold_seat_count+confirmed_ticket_count
                """).param("account",accountId).param("event",eventId).param("now",Timestamp.from(now))
            .query(Integer.class).single();
    }

    boolean hasNonOfferHold(UUID accountId,UUID eventId,Instant now) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM seat_hold WHERE account_id=:account AND event_id=:event
                  AND ((state='ACTIVE' AND expires_at>:now) OR state='PAYMENT_PENDING'))
                """).param("account",accountId).param("event",eventId).param("now",Timestamp.from(now))
            .query(Boolean.class).single();
    }

    UUID insertOfferHold(EventRow event,UUID accountId,Instant now,Instant expiresAt,
                         PricingPolicy.Quote quote) {
        return insertHold(event.id(),accountId,"WAITLIST_OFFER",now,expiresAt,quote);
    }

    UUID insertActiveHold(EventRow event,UUID accountId,Instant now,Instant expiresAt,
                          OfferQuote offer) {
        UUID id=jdbc.sql("""
                INSERT INTO seat_hold(event_id,account_id,state,created_at,expires_at,currency,
                    subtotal_minor,discount_minor,service_fee_minor,total_minor)
                VALUES(:event,:account,'ACTIVE',:now,:expires,:currency,:subtotal,:discount,:fee,:total)
                RETURNING id
                """).param("event",event.id()).param("account",accountId).param("now",Timestamp.from(now))
            .param("expires",Timestamp.from(expiresAt)).param("currency",offer.currency())
            .param("subtotal",offer.subtotalMinor()).param("discount",offer.discountMinor())
            .param("fee",offer.serviceFeeMinor()).param("total",offer.totalMinor())
            .query(UUID.class).single();
        for(OfferLine line:offer.lines()) insertItem(id,event.id(),line,now,expiresAt);
        return id;
    }

    private UUID insertHold(UUID eventId,UUID accountId,String state,Instant now,Instant expiresAt,
                            PricingPolicy.Quote quote) {
        UUID id=jdbc.sql("""
                INSERT INTO seat_hold(event_id,account_id,state,created_at,expires_at,currency,
                    subtotal_minor,discount_minor,service_fee_minor,total_minor)
                VALUES(:event,:account,:state,:now,:expires,:currency,:subtotal,:discount,:fee,:total)
                RETURNING id
                """).param("event",eventId).param("account",accountId).param("state",state)
            .param("now",Timestamp.from(now)).param("expires",Timestamp.from(expiresAt))
            .param("currency",quote.currency()).param("subtotal",quote.subtotalMinor())
            .param("discount",quote.discountMinor()).param("fee",quote.serviceFeeMinor())
            .param("total",quote.totalMinor()).query(UUID.class).single();
        for(PricingPolicy.Line line:quote.lines()) insertItem(id,eventId,new OfferLine(line.seatId(),
            line.basePriceMinor(),line.earlyDiscountMinor(),line.groupDiscountMinor(),
            line.promoDiscountMinor(),line.serviceFeeMinor(),line.finalTotalMinor(),quote.currency()),now,expiresAt);
        return id;
    }

    private void insertItem(UUID holdId,UUID eventId,OfferLine line,Instant starts,Instant ends) {
        jdbc.sql("""
                INSERT INTO seat_hold_item(hold_id,event_id,event_seat_id,claim_started_at,claim_ends_at,
                    base_price_minor,early_discount_minor,group_discount_minor,promo_discount_minor,
                    service_fee_minor,final_total_minor,currency)
                VALUES(:hold,:event,:seat,:starts,:ends,:base,:early,:groupDiscount,:promo,:fee,:total,:currency)
                """).param("hold",holdId).param("event",eventId).param("seat",line.seatId())
            .param("starts",Timestamp.from(starts)).param("ends",Timestamp.from(ends))
            .param("base",line.basePriceMinor()).param("early",line.earlyDiscountMinor())
            .param("groupDiscount",line.groupDiscountMinor()).param("promo",line.promoDiscountMinor())
            .param("fee",line.serviceFeeMinor()).param("total",line.finalTotalMinor())
            .param("currency",line.currency()).update();
    }

    void addActiveCount(UUID accountId,UUID eventId,int quantity) {
        jdbc.sql("""
                UPDATE customer_event_limit
                SET active_hold_seat_count=active_hold_seat_count+:quantity,version=version+1
                WHERE account_id=:account AND event_id=:event
                """).param("quantity",quantity).param("account",accountId).param("event",eventId).update();
    }

    void offer(UUID entryId,UUID holdId,Instant now,Instant expiresAt) {
        jdbc.sql("""
                UPDATE waitlist_entry SET state='OFFERED',offer_hold_id=:hold,offered_at=:now,
                    offer_expires_at=:expires,updated_at=:now,version=version+1
                WHERE id=:id AND state='WAITING'
                """).param("hold",holdId).param("now",Timestamp.from(now))
            .param("expires",Timestamp.from(expiresAt)).param("id",entryId).update();
    }

    List<UUID> lockOfferSeats(UUID holdId) {
        return jdbc.sql("""
                SELECT es.id FROM seat_hold_item i JOIN event_seat es ON es.id=i.event_seat_id
                WHERE i.hold_id=:hold ORDER BY es.id FOR UPDATE OF es
                """).param("hold",holdId).query(UUID.class).list();
    }

    OfferQuote lockOffer(UUID holdId,UUID accountId) {
        var hold=jdbc.sql("""
                SELECT id,currency,subtotal_minor,discount_minor,service_fee_minor,total_minor
                FROM seat_hold WHERE id=:id AND account_id=:account AND state='WAITLIST_OFFER'
                FOR UPDATE
                """).param("id",holdId).param("account",accountId).query((rs,n)->new long[]{
                    rs.getLong("subtotal_minor"),rs.getLong("discount_minor"),
                    rs.getLong("service_fee_minor"),rs.getLong("total_minor")}).single();
        String currency=jdbc.sql("SELECT currency FROM seat_hold WHERE id=:id").param("id",holdId)
            .query(String.class).single().strip();
        List<OfferLine> lines=jdbc.sql("""
                SELECT event_seat_id,base_price_minor,early_discount_minor,group_discount_minor,
                    promo_discount_minor,service_fee_minor,final_total_minor,currency
                FROM seat_hold_item WHERE hold_id=:hold ORDER BY event_seat_id FOR UPDATE
                """).param("hold",holdId).query((rs,n)->new OfferLine(rs.getObject("event_seat_id",UUID.class),
                    rs.getLong("base_price_minor"),rs.getLong("early_discount_minor"),
                    rs.getLong("group_discount_minor"),rs.getLong("promo_discount_minor"),
                    rs.getLong("service_fee_minor"),rs.getLong("final_total_minor"),
                    rs.getString("currency").strip())).list();
        return new OfferQuote(currency,hold[0],hold[1],hold[2],hold[3],lines);
    }

    void endOffer(UUID holdId,Instant now,String state) {
        jdbc.sql("UPDATE seat_hold_item SET claim_ends_at=:now WHERE hold_id=:hold")
            .param("now",Timestamp.from(now)).param("hold",holdId).update();
        jdbc.sql("""
                UPDATE seat_hold SET state=:state,ended_at=:now,version=version+1 WHERE id=:hold
                """).param("state",state).param("now",Timestamp.from(now)).param("hold",holdId).update();
    }

    void claim(UUID entryId,UUID claimedHold,Instant now) {
        jdbc.sql("""
                UPDATE waitlist_entry SET state='CLAIMED',claimed_hold_id=:hold,resolved_at=:now,
                    updated_at=:now,version=version+1 WHERE id=:id AND state='OFFERED'
                """).param("hold",claimedHold).param("now",Timestamp.from(now)).param("id",entryId).update();
    }

    void cancel(UUID entryId,Instant now) {
        jdbc.sql("""
                UPDATE waitlist_entry SET state='CANCELLED',resolved_at=:now,updated_at=:now,
                    version=version+1 WHERE id=:id AND state IN ('WAITING','OFFERED')
                """).param("now",Timestamp.from(now)).param("id",entryId).update();
    }

    void audit(UUID actor,String action,UUID entity,String reason,String before,String after,Instant now) {
        jdbc.sql("""
                INSERT INTO admin_audit(actor_account_id,action,entity_type,entity_id,reason,
                    before_state,after_state,created_at)
                VALUES(:actor,:action,'WAITLIST_ENTRY',:entity,:reason,
                    jsonb_build_object('value',:before),jsonb_build_object('value',:after),:now)
                """).param("actor",actor).param("action",action).param("entity",entity)
            .param("reason",reason).param("before",before).param("after",after)
            .param("now",Timestamp.from(now)).update();
    }

    void outbox(UUID id,String type,Instant now) {
        jdbc.sql("""
                INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,payload,created_at)
                VALUES('WAITLIST_ENTRY',:id,:type,:payload,:now)
                """).param("id",id).param("type",type).param("payload","{\"waitlistEntryId\":\""+id+"\"}")
            .param("now",Timestamp.from(now)).update();
    }

    private EntryRow entry(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
        return new EntryRow(rs.getObject("id",UUID.class),rs.getLong("enqueue_sequence"),
            rs.getObject("event_id",UUID.class),rs.getObject("account_id",UUID.class),
            rs.getObject("preferred_section_id",UUID.class),rs.getInt("quantity"),rs.getString("state"),
            rs.getObject("offer_hold_id",UUID.class),rs.getObject("claimed_hold_id",UUID.class),
            instant(rs,"offered_at"),instant(rs,"offer_expires_at"),
            rs.getTimestamp("created_at").toInstant());
    }

    private Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException {
        Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }

    private static final String ENTRY_SELECT="""
        SELECT w.id,w.enqueue_sequence,w.event_id,w.account_id,w.preferred_section_id,w.quantity,
               w.state,w.offer_hold_id,w.claimed_hold_id,w.offered_at,w.offer_expires_at,w.created_at
        FROM waitlist_entry w
        """;
    private static final String AVAILABLE_SELECT="""
        SELECT es.id,pt.price_minor,pt.currency
        FROM event_seat es
        JOIN seat s ON s.id=es.source_seat_id
        JOIN price_tier pt ON pt.id=es.price_tier_id
        WHERE es.event_id=:event AND es.inventory_state='AVAILABLE'
          AND (CAST(:section AS uuid) IS NULL OR s.section_id=:section)
          AND NOT EXISTS(SELECT 1 FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id
              WHERE i.event_seat_id=es.id AND ((h.state IN ('ACTIVE','WAITLIST_OFFER')
                AND h.expires_at>:now) OR h.state='PAYMENT_PENDING'))
          AND NOT EXISTS(SELECT 1 FROM ticket t WHERE t.event_seat_id=es.id
              AND t.state IN ('CONFIRMED','CANCELLATION_PENDING'))
        """;

    record EventRow(UUID id,UUID venueId,String state,Instant salesStartAt,Instant salesEndAt,
                    String currency,long serviceFeeMinor,Instant earlyBirdEndsAt,
                    int earlyBirdDiscountBps,Integer groupSizeThreshold,int groupDiscountBps){}
    record SeatRow(UUID id,long priceMinor,String currency){}
    record EntryRow(UUID id,long sequence,UUID eventId,UUID accountId,UUID preferredSectionId,
                    int quantity,String state,UUID offerHoldId,UUID claimedHoldId,
                    Instant offeredAt,Instant offerExpiresAt,Instant createdAt){}
    record OfferLine(UUID seatId,long basePriceMinor,long earlyDiscountMinor,long groupDiscountMinor,
                     long promoDiscountMinor,long serviceFeeMinor,long finalTotalMinor,String currency){}
    record OfferQuote(String currency,long subtotalMinor,long discountMinor,long serviceFeeMinor,
                      long totalMinor,List<OfferLine> lines){}
}
