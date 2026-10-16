package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(classes=WaitlistReportIntegrationTest.FixedClockConfiguration.class)
@Testcontainers
class WaitlistReportIntegrationTest {
    static final Instant NOW=Instant.parse("2030-06-01T12:00:00Z");
    static final String ADMIN="W".repeat(43),ONE="X".repeat(43),TWO="Y".repeat(43),THREE="Z".repeat(43);
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    @Autowired JdbcClient jdbc;@Autowired WebApplicationContext context;MockMvc mvc;
    UUID admin,one,two,three;

    @BeforeEach void setup()throws Exception {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE admin_audit").update();
        for(String table:new String[]{"event_reschedule","outbox_event","refund_attempt","refund_item","refund",
            "promo_redemption","payment_attempt","ticket","booking","waitlist_entry","seat_hold_item","seat_hold",
            "customer_event_limit","promo_code","event_seat","price_tier","event","seat","section","venue",
            "access_session","login_throttle","account"})jdbc.sql("DELETE FROM "+table).update();
        admin=account("admin@wait.test","ADMIN",ADMIN);one=account("one@wait.test","CUSTOMER",ONE);
        two=account("two@wait.test","CUSTOMER",TWO);three=account("three@wait.test","CUSTOMER",THREE);
    }

    @Test void enrollmentRequiresSoldOutMatchingInventoryAndOneOwnedActiveEntry()throws Exception {
        Fixture f=event(1);UUID seat=f.seats().getFirst();
        mvc.perform(join(f.event(),null,1,ONE)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("WAITLIST_NOT_NEEDED"));
        block(seat);
        mvc.perform(join(f.event(),null,1,ADMIN)).andExpect(status().isForbidden());
        Fixture other=event(1);block(other.seats().getFirst());
        mvc.perform(join(f.event(),other.section(),1,ONE)).andExpect(status().isBadRequest());
        mvc.perform(join(f.event(),f.section(),1,ONE)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("WAITING"));
        mvc.perform(join(f.event(),null,1,ONE)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ACTIVE_WAITLIST_EXISTS"));
        mvc.perform(join(f.event(),null,7,TWO)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/waitlist"),ONE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(auth(get("/api/waitlist"),TWO)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        UUID entry=jdbc.sql("SELECT id FROM waitlist_entry WHERE account_id=:account")
            .param("account",one).query(UUID.class).single();
        mvc.perform(auth(delete("/api/waitlist/{id}",entry),TWO)).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/waitlist/{id}",entry),ONE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CANCELLED"));
        mvc.perform(join(f.event(),null,1,ONE)).andExpect(status().isCreated());
        assertThat(count("waitlist_entry")).isEqualTo(2);
    }

    @Test void earliestEligibleEntryIsOfferedAndClaimCreatesFreshTenMinuteHold()throws Exception {
        Fixture f=event(3);f.seats().forEach(this::block);
        UUID first=joinId(f.event(),2,ONE);UUID second=joinId(f.event(),1,TWO);
        release(f.seats().getFirst());
        mvc.perform(auth(post("/api/admin/events/{id}/waitlist/promotions",f.event())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"),ADMIN))
            .andExpect(status().isBadRequest());
        assertThat(count("admin_audit")).isZero();
        mvc.perform(promote(f.event(),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.entryId").value(second.toString()))
            .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(900).toString()));
        UUID offer=jdbc.sql("SELECT offer_hold_id FROM waitlist_entry WHERE id=:id")
            .param("id",second).query(UUID.class).single();
        mvc.perform(checkout(offer,"offer-bypass",TWO)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("HOLD_NOT_ACTIVE"));
        mvc.perform(auth(post("/api/waitlist/{id}/claim",second),THREE)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/waitlist/{id}/claim",second),TWO)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(600).toString()));
        UUID claimed=jdbc.sql("SELECT claimed_hold_id FROM waitlist_entry WHERE id=:id")
            .param("id",second).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT state FROM seat_hold WHERE id=:id").param("id",offer).query(String.class).single())
            .isEqualTo("RELEASED");
        mvc.perform(checkout(claimed,"waitlist-checkout",TWO)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("CONFIRMED"));
        release(f.seats().get(1));release(f.seats().get(2));
        mvc.perform(promote(f.event(),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.entryId").value(first.toString()));
        assertThat(count("admin_audit")).isEqualTo(2);
    }

    @Test void promotionAndDirectHoldRaceLeavesExactlyOneSeatOwner()throws Exception {
        for(int round=0;round<10;round++) {
            Fixture f=event(1);UUID seat=f.seats().getFirst();block(seat);joinId(f.event(),1,ONE);release(seat);
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Integer> promote=()->{ready.countDown();start.await();return mvc.perform(
                    promote(f.event(),ADMIN)).andReturn().getResponse().getStatus();};
                Callable<Integer> hold=()->{ready.countDown();start.await();return mvc.perform(
                    createHold(f.event(),seat,TWO)).andReturn().getResponse().getStatus();};
                var a=executor.submit(promote);var b=executor.submit(hold);ready.await();start.countDown();
                List<Integer> outcomes=List.of(a.get(),b.get());
                assertThat(outcomes.equals(List.of(200,409))||outcomes.equals(List.of(409,200))
                    ||outcomes.equals(List.of(409,201))||outcomes.equals(List.of(201,409))).isTrue();
            }
            int owners=jdbc.sql("""
                SELECT count(*) FROM seat_hold h JOIN seat_hold_item i ON i.hold_id=h.id
                WHERE i.event_seat_id=:seat AND h.state IN ('ACTIVE','WAITLIST_OFFER') AND h.expires_at>:now
                """).param("seat",seat).param("now",Timestamp.from(NOW)).query(Integer.class).single();
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test void customerAtLimitIsSkippedAndOfferExpiresAtExactBoundary()throws Exception {
        Fixture f=event(1);UUID seat=f.seats().getFirst();block(seat);
        UUID skipped=joinId(f.event(),1,ONE);UUID offered=joinId(f.event(),1,TWO);
        jdbc.sql("INSERT INTO customer_event_limit(account_id,event_id,confirmed_ticket_count)VALUES(:a,:e,12)")
            .param("a",one).param("e",f.event()).update();release(seat);
        mvc.perform(promote(f.event(),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.entryId").value(offered.toString()));
        assertThat(state(skipped)).isEqualTo("CANCELLED");
        UUID offer=jdbc.sql("SELECT offer_hold_id FROM waitlist_entry WHERE id=:id")
            .param("id",offered).query(UUID.class).single();
        jdbc.sql("UPDATE waitlist_entry SET offered_at=:start,offer_expires_at=:end WHERE id=:id")
            .param("start",Timestamp.from(NOW.minusSeconds(900))).param("end",Timestamp.from(NOW)).param("id",offered).update();
        jdbc.sql("UPDATE seat_hold SET created_at=:start,expires_at=:end WHERE id=:id")
            .param("start",Timestamp.from(NOW.minusSeconds(900))).param("end",Timestamp.from(NOW)).param("id",offer).update();
        jdbc.sql("UPDATE seat_hold_item SET claim_started_at=:start,claim_ends_at=:end WHERE hold_id=:id")
            .param("start",Timestamp.from(NOW.minusSeconds(900))).param("end",Timestamp.from(NOW)).param("id",offer).update();
        mvc.perform(auth(post("/api/waitlist/{id}/claim",offered),TWO)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("WAITLIST_OFFER_EXPIRED"));
        assertThat(state(offered)).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT state FROM seat_hold WHERE id=:id").param("id",offer).query(String.class).single())
            .isEqualTo("EXPIRED");
    }

    @Test void salesAndOccupancyReportsReconcileChargesRefundsAndEverySeat()throws Exception {
        Fixture f=event(3);UUID sold=f.seats().getFirst(),blocked=f.seats().get(1),offered=f.seats().get(2);
        mvc.perform(createHold(f.event(),sold,ONE)).andExpect(status().isCreated());
        UUID hold=jdbc.sql("SELECT id FROM seat_hold WHERE event_id=:event AND account_id=:account")
            .param("event",f.event()).param("account",one).query(UUID.class).single();
        mvc.perform(checkout(hold,"report-checkout",ONE)).andExpect(status().isCreated());
        UUID ticket=jdbc.sql("SELECT id FROM ticket WHERE event_id=:event").param("event",f.event()).query(UUID.class).single();
        UUID booking=jdbc.sql("SELECT booking_id FROM ticket WHERE id=:id").param("id",ticket).query(UUID.class).single();
        block(blocked);block(offered);joinId(f.event(),1,ONE);release(offered);
        mvc.perform(promote(f.event(),ADMIN)).andExpect(status().isOk());
        assertThat(jdbc.sql("""
                SELECT active_hold_seat_count||':'||confirmed_ticket_count
                FROM customer_event_limit WHERE account_id=:account AND event_id=:event
                """).param("account",one).param("event",f.event()).query(String.class).single())
            .isEqualTo("1:1");
        mvc.perform(auth(post("/api/bookings/{id}/cancellations",booking).header("Idempotency-Key","report-refund")
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticketIds\":[\""+ticket+"\"]}"),ONE))
            .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/admin/reports/events/{id}/sales",f.event()),ONE)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/admin/reports/events/{id}/sales",f.event()),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.grossSalesMinor").value(1100))
            .andExpect(jsonPath("$.refundedMinor").value(1000))
            .andExpect(jsonPath("$.netSalesMinor").value(100))
            .andExpect(jsonPath("$.refundedTicketCount").value(1));
        mvc.perform(auth(get("/api/admin/reports/events/{id}/occupancy",f.event()),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalSeats").value(3)).andExpect(jsonPath("$.blockedSeats").value(1))
            .andExpect(jsonPath("$.waitlistOfferedSeats").value(1)).andExpect(jsonPath("$.unallocatedSeats").value(1))
            .andExpect(jsonPath("$.reconciledSeats").value(3));
    }

    @Test void eventCancellationClosesWaitingAndOfferedEntries()throws Exception {
        Fixture f=event(2);f.seats().forEach(this::block);
        UUID offered=joinId(f.event(),1,ONE);UUID waiting=joinId(f.event(),1,TWO);
        release(f.seats().getFirst());mvc.perform(promote(f.event(),ADMIN)).andExpect(status().isOk());
        UUID offer=jdbc.sql("SELECT offer_hold_id FROM waitlist_entry WHERE id=:id")
            .param("id",offered).query(UUID.class).single();
        release(f.seats().get(1));mvc.perform(createHold(f.event(),f.seats().get(1),THREE))
            .andExpect(status().isCreated());
        UUID ordinary=jdbc.sql("SELECT id FROM seat_hold WHERE event_id=:event AND account_id=:account")
            .param("event",f.event()).param("account",three).query(UUID.class).single();
        mvc.perform(auth(post("/api/admin/events/{id}/cancel",f.event()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"event cancelled\"}"),ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        assertThat(state(offered)).isEqualTo("CANCELLED");assertThat(state(waiting)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT state FROM seat_hold WHERE id=:id").param("id",offer).query(String.class).single())
            .isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT state FROM seat_hold WHERE id=:id").param("id",ordinary).query(String.class).single())
            .isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT active_hold_seat_count FROM customer_event_limit WHERE account_id=:a AND event_id=:e")
            .param("a",three).param("e",f.event()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT state FROM event WHERE id=:id").param("id",f.event()).query(String.class).single())
            .isEqualTo("CANCELLED");
    }

    private UUID joinId(UUID event,int quantity,String token)throws Exception {
        mvc.perform(join(event,null,quantity,token)).andExpect(status().isCreated());
        UUID account=token.equals(ONE)?one:token.equals(TWO)?two:three;
        return jdbc.sql("SELECT id FROM waitlist_entry WHERE event_id=:event AND account_id=:account AND state='WAITING'")
            .param("event",event).param("account",account).query(UUID.class).single();
    }
    private MockHttpServletRequestBuilder join(UUID event,UUID section,int quantity,String token) {
        String preference=section==null?"null":"\""+section+"\"";
        return auth(post("/api/events/{id}/waitlist",event).contentType(MediaType.APPLICATION_JSON)
            .content("{\"preferredSectionId\":"+preference+",\"quantity\":"+quantity+"}"),token);}
    private MockHttpServletRequestBuilder promote(UUID event,String token){return auth(
        post("/api/admin/events/{id}/waitlist/promotions",event).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"inventory released\"}"),token);}
    private MockHttpServletRequestBuilder createHold(UUID event,UUID seat,String token){return auth(
        post("/api/events/{id}/holds",event).contentType(MediaType.APPLICATION_JSON)
            .content("{\"seatIds\":[\""+seat+"\"]}"),token);}
    private MockHttpServletRequestBuilder checkout(UUID hold,String key,String token){return auth(
        post("/api/holds/{id}/checkout",hold).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{}"),token);}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String token){return request.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);}
    private void block(UUID seat){jdbc.sql("UPDATE event_seat SET inventory_state='BLOCKED' WHERE id=:id").param("id",seat).update();}
    private void release(UUID seat){jdbc.sql("UPDATE event_seat SET inventory_state='AVAILABLE' WHERE id=:id").param("id",seat).update();}
    private String state(UUID entry){return jdbc.sql("SELECT state FROM waitlist_entry WHERE id=:id").param("id",entry).query(String.class).single();}

    private Fixture event(int quantity){UUID venue=UUID.randomUUID(),section=UUID.randomUUID(),event=UUID.randomUUID();
        jdbc.sql("INSERT INTO venue(id,name,time_zone,address)VALUES(:id,'V','UTC','A')").param("id",venue).update();
        jdbc.sql("INSERT INTO section(id,venue_id,name,display_order)VALUES(:id,:v,:name,0)")
            .param("id",section).param("v",venue).param("name","S"+section.toString().substring(0,6)).update();
        jdbc.sql("""
            INSERT INTO event(id,venue_id,name,state,sales_start_at,sales_end_at,starts_at,time_zone,currency,service_fee_minor)
            VALUES(:id,:v,'Wait Event','ON_SALE',:from,:until,:starts,'UTC','USD',100)
            """).param("id",event).param("v",venue).param("from",Timestamp.from(NOW.minusSeconds(86400)))
            .param("until",Timestamp.from(NOW.plusSeconds(9*86400))).param("starts",Timestamp.from(NOW.plusSeconds(10*86400))).update();
        var seats=new ArrayList<UUID>();for(int i=0;i<quantity;i++)seats.add(seat(event,section,String.valueOf(i+1),i));
        return new Fixture(event,section,List.copyOf(seats));}
    private UUID seat(UUID event,UUID section,String number,int order){UUID source=UUID.randomUUID(),tier=UUID.randomUUID(),seat=UUID.randomUUID();
        jdbc.sql("INSERT INTO seat(id,section_id,row_label,seat_number,display_order)VALUES(:id,:s,'A',:n,:o)")
            .param("id",source).param("s",section).param("n",number).param("o",order).update();
        jdbc.sql("INSERT INTO price_tier(id,event_id,name,price_minor,currency)VALUES(:id,:e,:n,1000,'USD')")
            .param("id",tier).param("e",event).param("n","T"+number).update();
        jdbc.sql("""
            INSERT INTO event_seat(id,event_id,source_seat_id,price_tier_id,section_name,row_label,seat_number,display_order,accessible)
            VALUES(:id,:e,:source,:tier,'S','A',:n,:o,false)
            """).param("id",seat).param("e",event).param("source",source).param("tier",tier)
            .param("n",number).param("o",order).update();return seat;}
    private UUID account(String email,String role,String token)throws Exception {UUID id=jdbc.sql("""
            INSERT INTO account(email,password_hash,role)
            VALUES(:email,'$2a$12$01234567890123456789012345678901234567890123456789012',:role) RETURNING id
            """).param("email",email).param("role",role).query(UUID.class).single();
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO access_session(token_hash,account_id,created_at,expires_at)VALUES(:hash,:id,:now,:end)")
            .param("hash",hash).param("id",id).param("now",Timestamp.from(NOW.minusSeconds(1)))
            .param("end",Timestamp.from(NOW.plusSeconds(86400))).update();return id;}
    private int count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single();}
    record Fixture(UUID event,UUID section,List<UUID> seats){}
    @TestConfiguration static class FixedClockConfiguration{@Bean @Primary Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
}
