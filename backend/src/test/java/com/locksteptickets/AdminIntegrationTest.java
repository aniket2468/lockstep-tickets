package com.locksteptickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.locksteptickets.checkout.MockPaymentGateway;

@SpringBootTest(classes=AdminIntegrationTest.FixedClockConfiguration.class)
@Testcontainers
class AdminIntegrationTest {
    static final Instant NOW=Instant.parse("2030-06-01T12:00:00Z");
    static final String ADMIN="A".repeat(43),CUSTOMER="C".repeat(43),OTHER="D".repeat(43);
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=
        new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    @Autowired JdbcClient jdbc;@Autowired WebApplicationContext context;
    @Autowired MockPaymentGateway gateway;MockMvc mvc;UUID admin,customer,other;

    @BeforeEach void setup()throws Exception{
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        gateway.clearSimulations();
        jdbc.sql("TRUNCATE admin_audit").update();
        for(String table:new String[]{"event_reschedule","outbox_event","refund_attempt","refund_item","refund",
            "promo_redemption","payment_attempt","ticket","booking","seat_hold_item","seat_hold",
            "customer_event_limit","promo_code","event_seat","price_tier","event","seat","section","venue",
            "access_session","login_throttle","account"})jdbc.sql("DELETE FROM "+table).update();
        admin=account("admin@admin.test","ADMIN",ADMIN);customer=account("customer@admin.test","CUSTOMER",CUSTOMER);
        other=account("other@admin.test","CUSTOMER",OTHER);
    }

    @Test void blockReleaseRequireAdminReasonAndAtomicAudit()throws Exception{
        Fixture f=event(NOW.plusSeconds(10*86400));UUID seat=seat(f,"1",0);
        mvc.perform(auth(post("/api/admin/events/{e}/seats/{s}/block",f.event(),seat)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"maintenance\"}"),CUSTOMER))
            .andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/admin/events/{e}/seats/{s}/block",f.event(),seat)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"),ADMIN))
            .andExpect(status().isBadRequest());
        assertThat(count("admin_audit")).isZero();
        mvc.perform(block(f.event(),seat,"maintenance",ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.inventoryState").value("BLOCKED"));
        mvc.perform(block(f.event(),seat,"again",ADMIN)).andExpect(status().isConflict());
        assertThat(count("admin_audit")).isEqualTo(1);
        mvc.perform(auth(post("/api/admin/events/{e}/seats/{s}/release",f.event(),seat)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"reopened\"}"),ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.inventoryState").value("AVAILABLE"));
        mvc.perform(auth(get("/api/admin/audit"),ADMIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
        assertThat(count("admin_audit")).isEqualTo(2);
    }

    @Test void heldAndSoldSeatsCannotBeBlockedAndFailuresAreUnaudited()throws Exception{
        Fixture held=event(NOW.plusSeconds(10*86400));UUID heldSeat=seat(held,"1",0);
        mvc.perform(createHold(held.event(),heldSeat,CUSTOMER)).andExpect(status().isCreated());
        mvc.perform(block(held.event(),heldSeat,"unsafe",ADMIN)).andExpect(status().isConflict());
        Confirmed sold=confirmed(1,NOW.plusSeconds(10*86400));
        mvc.perform(block(sold.event(),sold.seats().getFirst(),"unsafe",ADMIN)).andExpect(status().isConflict());
        assertThat(count("admin_audit")).isZero();
    }

    @Test void blockAndHoldContentionNeverAllocatesBlockedInventory()throws Exception{
        for(int round=0;round<10;round++){
            Fixture f=event(NOW.plusSeconds(10*86400));UUID seat=seat(f,String.valueOf(round+1),0);
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)){
                Callable<Integer> block=()->{ready.countDown();start.await();return mvc.perform(
                    block(f.event(),seat,"contention",ADMIN)).andReturn().getResponse().getStatus();};
                Callable<Integer> hold=()->{ready.countDown();start.await();return mvc.perform(
                    createHold(f.event(),seat,OTHER)).andReturn().getResponse().getStatus();};
                var a=executor.submit(block);var b=executor.submit(hold);ready.await();start.countDown();
                List<Integer> outcomes=List.of(a.get(),b.get());
                assertThat(outcomes.equals(List.of(200,409))||outcomes.equals(List.of(409,200))
                    ||outcomes.equals(List.of(409,201))||outcomes.equals(List.of(201,409))).isTrue();
            }
            boolean blocked=jdbc.sql("SELECT inventory_state='BLOCKED' FROM event_seat WHERE id=:id")
                .param("id",seat).query(Boolean.class).single();
            int active=jdbc.sql("SELECT count(*) FROM seat_hold_item i JOIN seat_hold h ON h.id=i.hold_id WHERE i.event_seat_id=:id AND h.state='ACTIVE'")
                .param("id",seat).query(Integer.class).single();
            assertThat((blocked?1:0)+active).isEqualTo(1);
        }
    }

    @Test void rescheduleOpensFeeInclusiveCustomerRefundWindow()throws Exception{
        Confirmed f=confirmed(1,NOW.plusSeconds(10*86400));Instant next=NOW.plusSeconds(12*86400);
        mvc.perform(auth(post("/api/admin/events/{id}/reschedule",f.event())
            .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\""+next+"\",\"reason\":\"artist conflict\"}"),ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refundWindowEndsAt").value(NOW.plusSeconds(14*86400).toString()));
        mvc.perform(customerCancel(f,"reschedule-refund-key",CUSTOMER)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.percentageBps").value(10000)).andExpect(jsonPath("$.amountMinor").value(1100));
        assertThat(jdbc.sql("SELECT kind FROM refund").query(String.class).single()).isEqualTo("RESCHEDULE");
        assertThat(jdbc.sql("SELECT service_fee_refund_minor FROM refund").query(Long.class).single()).isEqualTo(100);
        assertThat(count("admin_audit")).isEqualTo(1);
    }

    @Test void draftAllowsInventoryPreparationButRejectsScheduleOperations()throws Exception{
        Fixture f=event(NOW.plusSeconds(10*86400));UUID seat=seat(f,"1",0);
        jdbc.sql("UPDATE event SET state='DRAFT' WHERE id=:id").param("id",f.event()).update();
        mvc.perform(block(f.event(),seat,"prepare inventory",ADMIN)).andExpect(status().isOk());
        mvc.perform(auth(post("/api/admin/events/{id}/reschedule",f.event())
            .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\""+NOW.plusSeconds(12*86400)+"\",\"reason\":\"too early\"}"),ADMIN))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_NOT_MUTABLE"));
        mvc.perform(auth(post("/api/admin/events/{id}/cancel",f.event()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"too early\"}"),ADMIN))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_NOT_MUTABLE"));
        assertThat(jdbc.sql("SELECT state FROM event WHERE id=:id").param("id",f.event()).query(String.class).single())
            .isEqualTo("DRAFT");
        assertThat(count("event_reschedule")).isZero();
        assertThat(count("admin_audit")).isEqualTo(1);
    }

    @Test void eventCancellationRefundsRemainingChargeAndRestoresPromoExactlyOnce()throws Exception{
        Confirmed f=confirmed(1,NOW.plusSeconds(48*3600));UUID promo=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO promo_code(id,event_id,code,discount_type,discount_value,valid_from,valid_until,
              global_limit,redeemed_count)VALUES(:id,:event,'USED','FIXED',100,:from,:until,5,1)
            """).param("id",promo).param("event",f.event()).param("from",Timestamp.from(NOW.minusSeconds(1)))
            .param("until",Timestamp.from(NOW.plusSeconds(86400))).update();
        jdbc.sql("INSERT INTO promo_redemption(promo_id,account_id,booking_id,state,created_at)VALUES(:p,:a,:b,'CONSUMED',:now)")
            .param("p",promo).param("a",customer).param("b",f.booking()).param("now",Timestamp.from(NOW)).update();
        mvc.perform(customerCancel(f,"customer-half-key",CUSTOMER)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.amountMinor").value(500));
        mvc.perform(auth(post("/api/admin/events/{id}/cancel",f.event()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"venue closed\"}"),ADMIN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
            .andExpect(jsonPath("$.refunds[0].amountMinor").value(600));
        assertThat(jdbc.sql("SELECT sum(amount_minor) FROM refund WHERE state='SUCCEEDED'").query(Long.class).single()).isEqualTo(1100);
        assertThat(jdbc.sql("SELECT redeemed_count FROM promo_code WHERE id=:id").param("id",promo).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT state FROM promo_redemption").query(String.class).single()).isEqualTo("RESTORED");
        assertThat(jdbc.sql("SELECT confirmed_ticket_count FROM customer_event_limit").query(Integer.class).single()).isZero();
        mvc.perform(auth(post("/api/admin/events/{id}/cancel",f.event()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"repeat\"}"),ADMIN)).andExpect(status().isConflict());
        assertThat(count("admin_audit")).isEqualTo(1);
    }

    @Test void exceptionalRefundIsIdempotentFeeInclusiveAndAudited()throws Exception{
        Confirmed f=confirmed(2,NOW.plusSeconds(10*86400));UUID first=f.tickets().getFirst();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<Integer> request=()->{ready.countDown();start.await();return mvc.perform(
                exceptional(f.booking(),first,"admin-refund-key",ADMIN)).andReturn().getResponse().getStatus();};
            var one=executor.submit(request);var two=executor.submit(request);ready.await();start.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(201,200);
        }
        mvc.perform(exceptional(f.booking(),f.tickets().get(1),"admin-refund-key",ADMIN)).andExpect(status().isConflict());
        assertThat(count("refund")).isEqualTo(1);assertThat(count("refund_attempt")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT amount_minor FROM refund").query(Long.class).single()).isEqualTo(1100);
        assertThat(count("admin_audit")).isEqualTo(1);
    }

    @Test void pendingPaymentBlocksCancellationWithoutAudit()throws Exception{
        Fixture f=event(NOW.plusSeconds(10*86400));UUID seat=seat(f,"1",0);
        mvc.perform(createHold(f.event(),seat,CUSTOMER)).andExpect(status().isCreated());
        UUID hold=jdbc.sql("SELECT id FROM seat_hold WHERE event_id=:event").param("event",f.event()).query(UUID.class).single();
        gateway.simulateOnce("pending-admin-key",MockPaymentGateway.RequestedOutcome.TIMEOUT,
            MockPaymentGateway.LookupOutcome.UNKNOWN);
        mvc.perform(auth(post("/api/holds/{id}/checkout",hold).header("Idempotency-Key","pending-admin-key")
            .contentType(MediaType.APPLICATION_JSON).content("{}"),CUSTOMER))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("PAYMENT_UNKNOWN"));
        mvc.perform(auth(post("/api/admin/events/{id}/cancel",f.event()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"cannot yet\"}"),ADMIN))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVENT_PAYMENT_PENDING"));
        assertThat(jdbc.sql("SELECT state FROM event WHERE id=:id").param("id",f.event()).query(String.class).single()).isEqualTo("ON_SALE");
        assertThat(count("admin_audit")).isZero();
    }

    @Test void databaseMakesAuditRowsAppendOnly()throws Exception{
        Fixture f=event(NOW.plusSeconds(10*86400));UUID seat=seat(f,"1",0);
        mvc.perform(block(f.event(),seat,"permanent history",ADMIN)).andExpect(status().isOk());
        assertThatThrownBy(()->jdbc.sql("UPDATE admin_audit SET reason='tampered'").update())
            .hasMessageContaining("admin_audit is append-only");
        assertThatThrownBy(()->jdbc.sql("DELETE FROM admin_audit").update())
            .hasMessageContaining("admin_audit is append-only");
        assertThat(count("admin_audit")).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder block(UUID event,UUID seat,String reason,String token){return auth(
        post("/api/admin/events/{e}/seats/{s}/block",event,seat).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\""+reason+"\"}"),token);}
    private MockHttpServletRequestBuilder createHold(UUID event,UUID seat,String token){return auth(
        post("/api/events/{id}/holds",event).contentType(MediaType.APPLICATION_JSON)
            .content("{\"seatIds\":[\""+seat+"\"]}"),token);}
    private MockHttpServletRequestBuilder customerCancel(Confirmed f,String key,String token){return auth(
        post("/api/bookings/{id}/cancellations",f.booking()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticketIds\":[\""+f.tickets().getFirst()+
                "\"]}"),token);}
    private MockHttpServletRequestBuilder exceptional(UUID booking,UUID ticket,String key,String token){return auth(
        post("/api/admin/bookings/{id}/refunds",booking).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticketIds\":[\""+ticket+
                "\"],\"reason\":\"goodwill\"}"),token);}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String token){return request.header(HttpHeaders.AUTHORIZATION,"Bearer "+token);}

    private Fixture event(Instant starts){UUID venue=UUID.randomUUID(),section=UUID.randomUUID(),event=UUID.randomUUID();
        jdbc.sql("INSERT INTO venue(id,name,time_zone,address)VALUES(:id,'V','UTC','A')").param("id",venue).update();
        jdbc.sql("INSERT INTO section(id,venue_id,name,display_order)VALUES(:id,:v,:name,0)").param("id",section).param("v",venue).param("name","S"+section.toString().substring(0,6)).update();
        jdbc.sql("""
            INSERT INTO event(id,venue_id,name,state,sales_start_at,sales_end_at,starts_at,time_zone,currency,service_fee_minor)
            VALUES(:id,:v,'Admin Event','ON_SALE',:from,:until,:starts,'UTC','USD',100)
            """).param("id",event).param("v",venue).param("from",Timestamp.from(NOW.minusSeconds(86400)))
            .param("until",Timestamp.from(starts.minusSeconds(1))).param("starts",Timestamp.from(starts)).update();return new Fixture(event,section);}
    private UUID seat(Fixture f,String number,int order){UUID source=UUID.randomUUID(),tier=UUID.randomUUID(),seat=UUID.randomUUID();
        jdbc.sql("INSERT INTO seat(id,section_id,row_label,seat_number,display_order)VALUES(:id,:s,'A',:n,:o)")
            .param("id",source).param("s",f.section()).param("n",number).param("o",order).update();
        jdbc.sql("INSERT INTO price_tier(id,event_id,name,price_minor,currency)VALUES(:id,:e,:n,1000,'USD')")
            .param("id",tier).param("e",f.event()).param("n","T"+number).update();
        jdbc.sql("""
            INSERT INTO event_seat(id,event_id,source_seat_id,price_tier_id,section_name,row_label,seat_number,display_order,accessible)
            VALUES(:id,:e,:source,:tier,'S','A',:n,:o,false)
            """).param("id",seat).param("e",f.event()).param("source",source).param("tier",tier).param("n",number).param("o",order).update();return seat;}
    private Confirmed confirmed(int quantity,Instant starts){Fixture f=event(starts);UUID hold=UUID.randomUUID(),booking=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO seat_hold(id,event_id,account_id,state,created_at,expires_at,ended_at,currency,
              subtotal_minor,discount_minor,service_fee_minor,total_minor)
            VALUES(:id,:e,:a,'CONSUMED',:created,:expires,:ended,'USD',:subtotal,0,:fee,:total)
            """).param("id",hold).param("e",f.event()).param("a",customer).param("created",Timestamp.from(NOW.minusSeconds(60)))
            .param("expires",Timestamp.from(NOW.plusSeconds(540))).param("ended",Timestamp.from(NOW))
            .param("subtotal",1000L*quantity).param("fee",100L*quantity).param("total",1100L*quantity).update();
        var seats=new java.util.ArrayList<UUID>();var tickets=new java.util.ArrayList<UUID>();
        for(int i=0;i<quantity;i++){UUID es=seat(f,String.valueOf(i+1),i);seats.add(es);
            jdbc.sql("""
                INSERT INTO seat_hold_item(hold_id,event_id,event_seat_id,claim_started_at,claim_ends_at,
                  base_price_minor,early_discount_minor,group_discount_minor,promo_discount_minor,service_fee_minor,final_total_minor,currency)
                VALUES(:h,:e,:s,:start,:end,1000,0,0,0,100,1100,'USD')
                """).param("h",hold).param("e",f.event()).param("s",es).param("start",Timestamp.from(NOW.minusSeconds(60))).param("end",Timestamp.from(NOW)).update();}
        jdbc.sql("""
            INSERT INTO booking(id,reference,account_id,event_id,hold_id,state,currency,subtotal_minor,discount_minor,
              service_fee_minor,total_minor,checkout_key,request_fingerprint,created_at,updated_at)
            VALUES(:id,:ref,:a,:e,:h,'CONFIRMED','USD',:subtotal,0,:fee,:total,:key,:fp,:now,:now)
            """).param("id",booking).param("ref","LT-"+booking.toString().substring(0,16)).param("a",customer).param("e",f.event())
            .param("h",hold).param("subtotal",1000L*quantity).param("fee",100L*quantity).param("total",1100L*quantity)
            .param("key","checkout-"+booking).param("fp",new byte[32]).param("now",Timestamp.from(NOW)).update();
        for(int i=0;i<quantity;i++){UUID ticket=UUID.randomUUID();tickets.add(ticket);jdbc.sql("""
            INSERT INTO ticket(id,booking_id,event_id,event_seat_id,state,base_price_minor,early_discount_minor,
              group_discount_minor,promo_discount_minor,service_fee_minor,charged_total_minor,currency,created_at)
            VALUES(:id,:b,:e,:s,'CONFIRMED',1000,0,0,0,100,1100,'USD',:now)
            """).param("id",ticket).param("b",booking).param("e",f.event()).param("s",seats.get(i)).param("now",Timestamp.from(NOW)).update();}
        jdbc.sql("INSERT INTO customer_event_limit(account_id,event_id,confirmed_ticket_count)VALUES(:a,:e,:count)")
            .param("a",customer).param("e",f.event()).param("count",quantity).update();return new Confirmed(f.event(),booking,List.copyOf(tickets),List.copyOf(seats));}
    private UUID account(String email,String role,String token)throws Exception{UUID id=jdbc.sql("INSERT INTO account(email,password_hash,role)VALUES(:e,'$2a$12$01234567890123456789012345678901234567890123456789012',:r)RETURNING id")
        .param("e",email).param("r",role).query(UUID.class).single();byte[] hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO access_session(token_hash,account_id,created_at,expires_at)VALUES(:h,:id,:now,:end)")
            .param("h",hash).param("id",id).param("now",Timestamp.from(NOW.minusSeconds(1))).param("end",Timestamp.from(NOW.plusSeconds(86400))).update();return id;}
    private int count(String table){return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single();}
    record Fixture(UUID event,UUID section){}record Confirmed(UUID event,UUID booking,List<UUID> tickets,List<UUID> seats){}
    @TestConfiguration static class FixedClockConfiguration{@Bean @Primary Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
}
