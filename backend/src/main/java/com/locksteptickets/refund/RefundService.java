package com.locksteptickets.refund;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.checkout.MockPaymentGateway;
import com.locksteptickets.hold.HoldRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {
    private final RefundRepository repository;
    private final RefundPolicy policy;
    private final MockPaymentGateway gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;

    RefundService(RefundRepository repository,RefundPolicy policy,MockPaymentGateway gateway,
                  TransactionTemplate transactions,Clock clock) {
        this.repository=repository;this.policy=policy;this.gateway=gateway;
        this.transactions=transactions;this.clock=clock;
    }

    public List<RefundRepository.BookingSummary> bookings(AuthenticatedAccount account) {
        requireCustomer(account); return repository.listBookings(account.accountId());
    }

    public BookingDetail booking(UUID id,AuthenticatedAccount account) {
        requireCustomer(account);
        var booking=repository.findOwnedBooking(id,account.accountId()).orElseThrow(ResourceNotFoundException::new);
        return new BookingDetail(booking.id(),booking.eventId(),booking.state(),booking.currency(),
            booking.startsAt(),repository.tickets(id));
    }

    public RefundResult cancel(UUID bookingId,List<UUID> requestedIds,String rawKey,
                               AuthenticatedAccount account) {
        requireCustomer(account);
        String key=key(rawKey); List<UUID> ids=ids(requestedIds); byte[] fingerprint=fingerprint(bookingId,ids);
        var scenario=gateway.scenarioFor(key);
        var requested=scenario.requested();var lookup=scenario.lookup();
        Prepared prepared=transactions.execute(status->prepare(bookingId,ids,key,fingerprint,requested,lookup,account.accountId()));
        if(prepared.replayed()) return new RefundResult(response(prepared.refund()),true);
        return new RefundResult(response(process(prepared.refund(),prepared.attempt(),requested)),false);
    }

    public RefundResponse retry(UUID refundId,String rawKey,AuthenticatedAccount account) {
        requireCustomer(account); String key=key(rawKey);
        var scenario=gateway.scenarioFor(key);
        var requested=scenario.requested();var lookup=scenario.lookup();
        Retry retry=transactions.execute(status->prepareRetry(refundId,key,requested,lookup,account.accountId()));
        if(retry.attempt()==0) return response(retry.refund());
        return response(process(retry.refund(),retry.attempt(),requested));
    }

    public RefundResponse reconcile(UUID refundId,AuthenticatedAccount account) {
        requireCustomer(account);
        var refund=repository.findOwnedRefund(refundId,account.accountId()).orElseThrow(ResourceNotFoundException::new);
        if("SUCCEEDED".equals(refund.state())) return response(refund);
        var attempt=repository.latestAttempt(refundId);
        if("SUCCEEDED".equals(attempt.state())) return response(transactions.execute(s->finalizeOutcome(refundId,"SUCCEEDED")));
        if("DECLINED".equals(attempt.state())) return response(transactions.execute(s->finalizeOutcome(refundId,"DECLINED")));
        var outcome=gateway.lookup(attempt.lookup());
        transactions.executeWithoutResult(s->repository.recordLookup(refundId,attempt.number(),outcome,clock.instant()));
        String durable=repository.latestAttempt(refundId).state();
        return response(transactions.execute(s->finalizeOutcome(refundId,durable)));
    }

    private Prepared prepare(UUID bookingId,List<UUID> ids,String key,byte[] fingerprint,
                             MockPaymentGateway.RequestedOutcome requested,
                             MockPaymentGateway.LookupOutcome lookup,UUID accountId) {
        var prior=repository.findByKey(accountId,key).orElse(null);
        if(prior!=null){verify(prior,fingerprint);return new Prepared(prior,0,true);}
        var initial=repository.findOwnedBooking(bookingId,accountId).orElseThrow(ResourceNotFoundException::new);
        var selected=repository.selectedTickets(bookingId,ids);
        if(selected.size()!=ids.size()) throw conflict("TICKETS_NOT_CANCELLABLE","One or more tickets cannot be cancelled.");
        repository.lockEvent(initial.eventId());
        prior=repository.findByKey(accountId,key).orElse(null);
        if(prior!=null){verify(prior,fingerprint);return new Prepared(prior,0,true);}
        repository.lockLimit(accountId,initial.eventId());
        repository.lockSeats(selected.stream().map(RefundRepository.Ticket::eventSeatId).sorted().toList());
        var booking=repository.lockBooking(bookingId,accountId);
        var tickets=repository.lockTickets(bookingId,ids);
        if(!List.of("CONFIRMED","PARTIALLY_REFUNDED").contains(booking.state())
            || tickets.size()!=ids.size() || tickets.stream().anyMatch(t->!"CONFIRMED".equals(t.state()))) {
            throw conflict("TICKETS_NOT_CANCELLABLE","One or more tickets cannot be cancelled.");
        }
        Instant policyNow=clock.instant();
        boolean reschedule=booking.refundWindowEndsAt()!=null&&!policyNow.isAfter(booking.refundWindowEndsAt());
        int bps=reschedule?10_000:policy.customerPercentageBps(policyNow,booking.startsAt());
        long amount=0;
        long serviceFeeRefund=0;
        for(var ticket:tickets) {
            long fee=reschedule?ticket.serviceFeeMinor():0;
            serviceFeeRefund=Math.addExact(serviceFeeRefund,fee);
            amount=Math.addExact(amount,Math.addExact(
                policy.amount(ticket.chargedTotalMinor(),ticket.serviceFeeMinor(),bps),fee));
        }
        Instant now=clock.instant(); UUID id=repository.createRefund(booking,key,fingerprint,
            reschedule?"RESCHEDULE":"CUSTOMER_CANCELLATION",bps,amount,serviceFeeRefund,now);
        repository.addItems(id,tickets,bps,reschedule); repository.markTicketsPending(ids);
        int attempt=repository.createAttempt(id,requested,lookup,now);
        return new Prepared(repository.findOwnedRefund(id,accountId).orElseThrow(),attempt,false);
    }

    private Retry prepareRetry(UUID refundId,String key,MockPaymentGateway.RequestedOutcome requested,
                               MockPaymentGateway.LookupOutcome lookup,UUID accountId) {
        var initial=repository.findOwnedRefund(refundId,accountId).orElseThrow(ResourceNotFoundException::new);
        if(!MessageDigest.isEqual(initial.key().getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))
            throw conflict("IDEMPOTENCY_KEY_REUSED","The refund key does not match the original request.");
        if("SUCCEEDED".equals(initial.state())||"REQUESTED".equals(initial.state())||"UNKNOWN".equals(initial.state()))
            return new Retry(initial,0);
        var tickets=repository.refundTickets(refundId);
        repository.lockEvent(initial.eventId()); repository.lockLimit(accountId,initial.eventId());
        repository.lockSeats(tickets.stream().map(RefundRepository.Ticket::eventSeatId).sorted().toList());
        repository.lockBooking(initial.bookingId(),accountId);
        repository.lockTickets(initial.bookingId(),tickets.stream().map(RefundRepository.Ticket::id).toList());
        var locked=repository.lockRefund(refundId);
        if(!"FAILED".equals(locked.state())) return new Retry(locked,0);
        Instant now=clock.instant(); repository.setRefundState(refundId,"REQUESTED",now);
        int attempt=repository.createAttempt(refundId,requested,lookup,now);
        return new Retry(repository.findOwnedRefund(refundId,accountId).orElseThrow(),attempt);
    }

    private RefundRepository.Refund process(RefundRepository.Refund refund,int attempt,
                                            MockPaymentGateway.RequestedOutcome requested) {
        var observed=gateway.charge(requested);
        transactions.executeWithoutResult(s->repository.recordObserved(refund.id(),attempt,observed,clock.instant()));
        String durable=repository.latestAttempt(refund.id()).state();
        return transactions.execute(s->finalizeOutcome(refund.id(),durable));
    }

    public RefundResponse processPrepared(UUID refundId,MockPaymentGateway.RequestedOutcome requested) {
        var accountId=accountFor(refundId);
        var refund=repository.findOwnedRefund(refundId,accountId).orElseThrow(ResourceNotFoundException::new);
        var attempt=repository.latestAttempt(refundId);
        if(!"INITIATED".equals(attempt.state())) return response(refund);
        return response(process(refund,attempt.number(),requested));
    }

    private RefundRepository.Refund finalizeOutcome(UUID refundId,String outcome) {
        UUID accountId=accountFor(refundId);
        var initial=repository.findOwnedRefund(refundId,accountId).orElseThrow();
        var tickets=repository.refundTickets(refundId);
        repository.lockEvent(initial.eventId()); repository.lockLimit(initial.accountId(),initial.eventId());
        repository.lockSeats(tickets.stream().map(RefundRepository.Ticket::eventSeatId).sorted().toList());
        repository.lockBooking(initial.bookingId(),initial.accountId());
        var lockedTickets=repository.lockTickets(initial.bookingId(),tickets.stream().map(RefundRepository.Ticket::id).toList());
        var refund=repository.lockRefund(refundId);
        if("SUCCEEDED".equals(refund.state())) return refund;
        Instant now=clock.instant();
        switch(outcome){
            case "SUCCEEDED"->repository.succeed(refund,lockedTickets,now);
            case "DECLINED"->repository.setRefundState(refundId,"FAILED",now);
            default->repository.setRefundState(refundId,"UNKNOWN",now);
        }
        return repository.findOwnedRefund(refundId,refund.accountId()).orElseThrow();
    }

    private UUID accountFor(UUID refundId){
        // Ownership is already checked at every public entry point; this resolves the locked flow.
        return repository.accountIdForRefund(refundId).orElseThrow(ResourceNotFoundException::new);
    }
    private List<UUID> ids(List<UUID> value){
        if(value==null||value.isEmpty()||value.size()>6)throw bad("Between one and six tickets must be selected.");
        var set=new HashSet<>(value);if(set.contains(null)||set.size()!=value.size())throw bad("Ticket identifiers must be unique.");
        var result=new ArrayList<>(set);result.sort(UUID::compareTo);return List.copyOf(result);
    }
    private String key(String raw){String value=raw.strip();if(value.length()<8||value.length()>100)throw bad("Invalid idempotency key.");return value;}
    private byte[] fingerprint(UUID booking,List<UUID> ids){try{return MessageDigest.getInstance("SHA-256")
        .digest((booking+"|"+ids).getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void verify(RefundRepository.Refund refund,byte[] fp){if(!MessageDigest.isEqual(refund.fingerprint(),fp))
        throw conflict("IDEMPOTENCY_KEY_REUSED","The idempotency key was already used for a different request.");}
    private void requireCustomer(AuthenticatedAccount a){if(a.role()!=Account.Role.CUSTOMER)throw new HoldRejectedException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.");}
    private HoldRejectedException bad(String m){return new HoldRejectedException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",m);}
    private HoldRejectedException conflict(String c,String m){return new HoldRejectedException(HttpStatus.CONFLICT,c,m);}
    private RefundResponse response(RefundRepository.Refund r){return new RefundResponse(r.id(),r.bookingId(),r.state(),r.percentageBps(),r.amountMinor(),r.currency());}

    private record Prepared(RefundRepository.Refund refund,int attempt,boolean replayed){}
    private record Retry(RefundRepository.Refund refund,int attempt){}
    public record RefundResult(RefundResponse refund,boolean replayed){}
    public record RefundResponse(UUID id,UUID bookingId,String state,int percentageBps,long amountMinor,String currency){}
    public record BookingDetail(UUID id,UUID eventId,String state,String currency,Instant startsAt,List<RefundRepository.Ticket> tickets){}
}
