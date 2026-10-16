package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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

@SpringBootTest(classes=RefundIntegrationTest.FixedClockConfiguration.class)
@Testcontainers
class RefundIntegrationTest {
    static final Instant NOW=Instant.parse("2030-03-08T12:00:00Z");
    static final String ONE="G".repeat(43),TWO="H".repeat(43);
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    @Autowired JdbcClient jdbc; @Autowired WebApplicationContext context;
    MockMvc mvc; UUID one,two;

    @BeforeEach void setup()throws Exception{
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("TRUNCATE admin_audit").update();
        for(String table:new String[]{"event_reschedule","outbox_event","refund_attempt","refund_item","refund","promo_redemption",
            "payment_attempt","ticket","booking","seat_hold_item","seat_hold","customer_event_limit","promo_code",
            "event_seat","price_tier","event","seat","section","venue","access_session","login_throttle","account"})
            jdbc.sql("DELETE FROM "+table).update();
        one=account("one@refund.test",ONE);two=account("two@refund.test",TWO);
    }

    @Test void bookingViewIsOwnedAndRedacted()throws Exception{
        Fixture f=booking(2,NOW.plusSeconds(8*24*3600));
        mvc.perform(auth(get("/api/bookings/{id}",f.booking()),ONE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tickets.length()").value(2))
            .andExpect(jsonPath("$.tickets[0].chargedTotalMinor").value(1100));
        mvc.perform(auth(get("/api/bookings/{id}",f.booking()),TWO)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/bookings"),ONE)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].reference").isNotEmpty());
    }

    @Test void fullRefundExcludesFeeAndReleasesOnlySelectedSeat()throws Exception{
        Fixture f=booking(2,NOW.plusSeconds(7*24*3600));
        mvc.perform(cancel(f.booking(),List.of(f.tickets().getFirst()),"refund-key-0001","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("SUCCEEDED"))
            .andExpect(jsonPath("$.percentageBps").value(10000)).andExpect(jsonPath("$.amountMinor").value(1000));
        mvc.perform(cancel(f.booking(),List.of(f.tickets().getFirst()),"refund-key-0001","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
        mvc.perform(cancel(f.booking(),List.of(f.tickets().get(1)),"refund-key-0001","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("refund")).isEqualTo(1);assertThat(count("refund_attempt")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT state FROM booking WHERE id=:id").param("id",f.booking()).query(String.class).single())
            .isEqualTo("PARTIALLY_REFUNDED");
        assertThat(jdbc.sql("SELECT confirmed_ticket_count FROM customer_event_limit").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(createHold(f.event(),f.seats().getFirst(),TWO)).andExpect(status().isCreated());
        mvc.perform(createHold(f.event(),f.seats().get(1),TWO)).andExpect(status().isConflict());
    }

    @Test void halfAndZeroBandsUseExactBoundaries()throws Exception{
        Fixture half=booking(1,NOW.plusSeconds(48*3600));
        mvc.perform(cancel(half.booking(),half.tickets(),"refund-key-0002","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.percentageBps").value(5000))
            .andExpect(jsonPath("$.amountMinor").value(500));
        Fixture zero=booking(1,NOW.plusSeconds(48*3600-1));
        mvc.perform(cancel(zero.booking(),zero.tickets(),"refund-key-0003","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.percentageBps").value(0))
            .andExpect(jsonPath("$.amountMinor").value(0));
    }

    @Test void rescheduleWindowEndIsInclusiveAndFeeRefundStopsOneSecondLater()throws Exception{
        Fixture inside=booking(1,NOW.plusSeconds(8*24*3600));
        rescheduleWindow(inside.event(),NOW);
        mvc.perform(cancel(inside.booking(),inside.tickets(),"reschedule-edge-1","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.amountMinor").value(1100));
        Fixture outside=booking(1,NOW.plusSeconds(8*24*3600));
        rescheduleWindow(outside.event(),NOW.minusSeconds(1));
        mvc.perform(cancel(outside.booking(),outside.tickets(),"reschedule-edge-2","SUCCESS","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.amountMinor").value(1000));
    }

    @Test void unknownKeepsSeatProtectedUntilOwnedReconciliation()throws Exception{
        Fixture f=booking(1,NOW.plusSeconds(8*24*3600));
        String body=mvc.perform(cancel(f.booking(),f.tickets(),"refund-key-0004","TIMEOUT","SUCCESS",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("UNKNOWN"))
            .andReturn().getResponse().getContentAsString();
        UUID refund=UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*","$1"));
        mvc.perform(createHold(f.event(),f.seats().getFirst(),TWO)).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/refunds/{id}/reconcile",refund),TWO)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/refunds/{id}/reconcile",refund),ONE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        mvc.perform(createHold(f.event(),f.seats().getFirst(),TWO)).andExpect(status().isCreated());
    }

    @Test void failedRefundCanRetryWithSameKeyWithoutRestoringSeatEarly()throws Exception{
        Fixture f=booking(1,NOW.plusSeconds(8*24*3600));
        String body=mvc.perform(cancel(f.booking(),f.tickets(),"refund-key-0005","DECLINE","UNKNOWN",ONE))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("FAILED"))
            .andReturn().getResponse().getContentAsString();
        UUID refund=UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*","$1"));
        mvc.perform(createHold(f.event(),f.seats().getFirst(),TWO)).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/refunds/{id}/retry",refund).header("Idempotency-Key","refund-key-0005")
            .contentType(MediaType.APPLICATION_JSON).content("{\"refundOutcome\":\"SUCCESS\"}"),ONE))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
        assertThat(count("refund")).isEqualTo(1);assertThat(count("refund_attempt")).isEqualTo(2);
    }

    @Test void idempotencyAndConcurrentOverRefundArePrevented()throws Exception{
        Fixture f=booking(1,NOW.plusSeconds(8*24*3600));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<Integer> a=contender(f,"refund-race-0001",ready,start);
            Callable<Integer> b=contender(f,"refund-race-0002",ready,start);
            var x=executor.submit(a);var y=executor.submit(b);ready.await();start.countDown();
            assertThat(List.of(x.get(),y.get())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count("refund")).isEqualTo(1);assertThat(count("refund_item")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT coalesce(sum(amount_minor),0) FROM refund WHERE state='SUCCEEDED'")
            .query(Long.class).single()).isEqualTo(1000);
    }

    Callable<Integer> contender(Fixture f,String key,CountDownLatch ready,CountDownLatch start){return()->{
        ready.countDown();start.await();return mvc.perform(cancel(f.booking(),f.tickets(),key,"SUCCESS","UNKNOWN",ONE))
            .andReturn().getResponse().getStatus();};}

    MockHttpServletRequestBuilder cancel(UUID booking,List<UUID> tickets,String key,String outcome,String lookup,String token){
        String ids=tickets.stream().map(id->"\""+id+"\"").reduce((a,b)->a+","+b).orElse("");
        return auth(post("/api/bookings/{id}/cancellations",booking).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticketIds\":["+ids+"],\"refundOutcome\":\""+outcome
                +"\",\"lookupOutcome\":\""+lookup+"\"}"),token);}
    MockHttpServletRequestBuilder createHold(UUID event,UUID seat,String token){return auth(post("/api/events/{id}/holds",event)
        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[\""+seat+"\"]}"),token);}
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r,String token){return r.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);}

    void rescheduleWindow(UUID event,Instant ends){jdbc.sql("""
        INSERT INTO event_reschedule(event_id,previous_starts_at,new_starts_at,announced_at,
          refund_window_ends_at,actor_account_id,reason)
        SELECT id,starts_at-interval '1 day',starts_at,:announced,:ends,:actor,'test' FROM event WHERE id=:event
        """).param("event",event).param("announced",Timestamp.from(ends.minusSeconds(14*24*3600)))
        .param("ends",Timestamp.from(ends)).param("actor",one).update();}

    Fixture booking(int count,Instant starts)throws Exception{
        UUID venue=UUID.randomUUID(),section=UUID.randomUUID(),event=UUID.randomUUID(),tier=UUID.randomUUID(),hold=UUID.randomUUID(),booking=UUID.randomUUID();
        jdbc.sql("INSERT INTO venue(id,name,time_zone,address)VALUES(:id,'V','America/New_York','A')").param("id",venue).update();
        jdbc.sql("INSERT INTO section(id,venue_id,name,display_order)VALUES(:id,:v,'S',0)").param("id",section).param("v",venue).update();
        jdbc.sql("""
            INSERT INTO event(id,venue_id,name,state,sales_start_at,sales_end_at,starts_at,time_zone,currency)
            VALUES(:id,:v,'Refund Event','ON_SALE',:ss,:se,:starts,'America/New_York','USD')
            """)
            .param("id",event).param("v",venue).param("ss",Timestamp.from(NOW.minusSeconds(86400)))
            .param("se",Timestamp.from(starts.minusSeconds(1))).param("starts",Timestamp.from(starts)).update();
        jdbc.sql("INSERT INTO price_tier(id,event_id,name,price_minor,currency)VALUES(:id,:e,'T',1000,'USD')")
            .param("id",tier).param("e",event).update();
        jdbc.sql("""
            INSERT INTO seat_hold(id,event_id,account_id,state,created_at,expires_at,ended_at,currency,
            subtotal_minor,discount_minor,service_fee_minor,total_minor)
            VALUES(:id,:e,:a,'CONSUMED',:created,:expires,:ended,'USD',:subtotal,0,:fee,:total)
            """)
            .param("id",hold).param("e",event).param("a",one).param("created",Timestamp.from(NOW.minusSeconds(60)))
            .param("expires",Timestamp.from(NOW.plusSeconds(540))).param("ended",Timestamp.from(NOW))
            .param("subtotal",1000L*count).param("fee",100L*count).param("total",1100L*count).update();
        var tickets=new java.util.ArrayList<UUID>();var seats=new java.util.ArrayList<UUID>();
        for(int i=0;i<count;i++){
            UUID source=UUID.randomUUID(),es=UUID.randomUUID(),ticket=UUID.randomUUID();tickets.add(ticket);seats.add(es);
            jdbc.sql("INSERT INTO seat(id,section_id,row_label,seat_number,display_order)VALUES(:id,:s,'A',:n,:o)")
                .param("id",source).param("s",section).param("n",String.valueOf(i+1)).param("o",i).update();
            jdbc.sql("""
                INSERT INTO event_seat(id,event_id,source_seat_id,price_tier_id,section_name,row_label,seat_number,display_order,accessible)
                VALUES(:id,:e,:source,:tier,'S','A',:n,:o,false)
                """).param("id",es).param("e",event).param("source",source)
                .param("tier",tier).param("n",String.valueOf(i+1)).param("o",i).update();
            jdbc.sql("""
                INSERT INTO seat_hold_item(hold_id,event_id,event_seat_id,claim_started_at,claim_ends_at,
                base_price_minor,early_discount_minor,group_discount_minor,promo_discount_minor,service_fee_minor,final_total_minor,currency)
                VALUES(:h,:e,:s,:start,:end,1000,0,0,0,100,1100,'USD')
                """).param("h",hold).param("e",event).param("s",es)
                .param("start",Timestamp.from(NOW.minusSeconds(60))).param("end",Timestamp.from(NOW)).update();
        }
        jdbc.sql("""
            INSERT INTO booking(id,reference,account_id,event_id,hold_id,state,currency,subtotal_minor,discount_minor,
            service_fee_minor,total_minor,checkout_key,request_fingerprint,created_at,updated_at)
            VALUES(:id,:reference,:a,:e,:h,'CONFIRMED','USD',:subtotal,0,:fee,:total,:checkoutKey,:fp,:now,:now)
            """)
            .param("id",booking).param("reference","LT-"+booking.toString().substring(0,16))
            .param("a",one).param("e",event).param("h",hold).param("checkoutKey","checkout-"+booking)
            .param("subtotal",1000L*count)
            .param("fee",100L*count).param("total",1100L*count).param("fp",new byte[32]).param("now",Timestamp.from(NOW)).update();
        for(int i=0;i<count;i++)jdbc.sql("""
            INSERT INTO ticket(id,booking_id,event_id,event_seat_id,state,base_price_minor,
            early_discount_minor,group_discount_minor,promo_discount_minor,service_fee_minor,charged_total_minor,currency,created_at)
            VALUES(:id,:b,:e,:s,'CONFIRMED',1000,0,0,0,100,1100,'USD',:now)
            """).param("id",tickets.get(i)).param("b",booking)
            .param("e",event).param("s",seats.get(i)).param("now",Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO customer_event_limit(account_id,event_id,confirmed_ticket_count)VALUES(:a,:e,:count)")
            .param("a",one).param("e",event).param("count",count).update();
        return new Fixture(event,booking,List.copyOf(tickets),List.copyOf(seats));
    }
    UUID account(String email,String token)throws Exception{UUID id=jdbc.sql("INSERT INTO account(email,password_hash,role)VALUES(:e,'$2a$12$01234567890123456789012345678901234567890123456789012','CUSTOMER')RETURNING id")
        .param("e",email).query(UUID.class).single();byte[] hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO access_session(token_hash,account_id,created_at,expires_at)VALUES(:h,:id,:n,:x)").param("h",hash).param("id",id)
            .param("n",Timestamp.from(NOW.minusSeconds(1))).param("x",Timestamp.from(NOW.plusSeconds(86400))).update();return id;}
    int count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single();}
    record Fixture(UUID event,UUID booking,List<UUID> tickets,List<UUID> seats){}
    @TestConfiguration static class FixedClockConfiguration{@Bean @Primary Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
}
