package com.locksteptickets.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.checkout.MockPaymentGateway;
import com.locksteptickets.hold.HoldRejectedException;
import com.locksteptickets.refund.RefundService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminService {
    private final AdminRepository repository;
    private final RefundService refunds;
    private final TransactionTemplate transactions;
    private final Clock clock;
    AdminService(AdminRepository repository,RefundService refunds,TransactionTemplate transactions,Clock clock){
        this.repository=repository;this.refunds=refunds;this.transactions=transactions;this.clock=clock;}

    public SeatResponse block(UUID eventId,UUID seatId,String rawReason,AuthenticatedAccount actor){
        return seatState(eventId,seatId,"BLOCKED","SEAT_BLOCKED",rawReason,actor);}
    public SeatResponse release(UUID eventId,UUID seatId,String rawReason,AuthenticatedAccount actor){
        return seatState(eventId,seatId,"AVAILABLE","SEAT_RELEASED",rawReason,actor);}

    private SeatResponse seatState(UUID eventId,UUID seatId,String target,String action,String rawReason,
                                   AuthenticatedAccount actor){
        requireAdmin(actor);String reason=reason(rawReason);
        return transactions.execute(status->{Instant now=clock.instant();var event=repository.lockEvent(eventId)
            .orElseThrow(ResourceNotFoundException::new);mutable(event);
            repository.expireHolds(eventId,now);var seat=repository.lockSeat(eventId,seatId)
                .orElseThrow(ResourceNotFoundException::new);
            if(seat.state().equals(target))throw conflict("SEAT_STATE_UNCHANGED","The seat already has the requested state.");
            if("BLOCKED".equals(target)&&repository.seatClaimed(seatId,now))
                throw conflict("SEAT_IN_USE","A held, payment-pending, or sold seat cannot be blocked.");
            repository.setSeatState(seatId,target);
            repository.audit(actor.accountId(),action,"EVENT_SEAT",seatId,reason,seat.state(),target,now);
            repository.outbox("EVENT_SEAT",seatId,action,now);return new SeatResponse(seatId,target);});
    }

    public RescheduleResponse reschedule(UUID eventId,Instant startsAt,String rawReason,AuthenticatedAccount actor){
        requireAdmin(actor);String reason=reason(rawReason);
        return transactions.execute(status->{Instant now=clock.instant();var event=repository.lockEvent(eventId)
            .orElseThrow(ResourceNotFoundException::new);scheduled(event);
            if(!startsAt.isAfter(event.salesEndAt())||!startsAt.isAfter(now)||startsAt.equals(event.startsAt()))
                throw conflict("INVALID_EVENT_TIME","The new start must be future, changed, and not precede sales end.");
            repository.reschedule(event,startsAt,actor.accountId(),reason,now);
            repository.audit(actor.accountId(),"EVENT_RESCHEDULED","EVENT",eventId,reason,
                event.startsAt().toString(),startsAt.toString(),now);
            repository.outbox("EVENT",eventId,"EVENT_RESCHEDULED",now);
            return new RescheduleResponse(eventId,event.state(),event.startsAt(),startsAt,now.plusSeconds(14*24*3600L));});
    }

    public CancellationResponse cancel(UUID eventId,String rawReason,
                                       MockPaymentGateway.RequestedOutcome requested,
                                       MockPaymentGateway.LookupOutcome lookup,AuthenticatedAccount actor){
        requireAdmin(actor);String reason=reason(rawReason);
        PreparedCancellation prepared=transactions.execute(status->prepareCancellation(
            eventId,reason,requested,lookup,actor.accountId()));
        var results=new ArrayList<RefundService.RefundResponse>();
        for(UUID id:prepared.refundIds())results.add(refunds.processPrepared(id,requested));
        return new CancellationResponse(eventId,"CANCELLED",List.copyOf(results));
    }

    private PreparedCancellation prepareCancellation(UUID eventId,String reason,
                                                     MockPaymentGateway.RequestedOutcome requested,
                                                     MockPaymentGateway.LookupOutcome lookup,UUID actor){
        Instant now=clock.instant();var event=repository.lockEvent(eventId).orElseThrow(ResourceNotFoundException::new);
        scheduled(event);if(repository.hasPendingPayment(eventId))
            throw conflict("EVENT_PAYMENT_PENDING","Resolve pending payments before cancelling the event.");
        var initial=repository.cancellableTickets(eventId);repository.lockLimits(eventId);
        repository.lockSeats(initial.stream().map(AdminRepository.TicketRow::eventSeatId).distinct().sorted().toList());
        repository.lockPromos(eventId);repository.lockBookings(eventId);var tickets=repository.lockTickets(eventId);
        var groups=new LinkedHashMap<UUID,List<AdminRepository.TicketRow>>();
        for(var ticket:tickets)groups.computeIfAbsent(ticket.bookingId(),ignored->new ArrayList<>()).add(ticket);
        var ids=new ArrayList<UUID>();
        for(var entry:groups.entrySet()){var group=entry.getValue();var first=group.getFirst();long amount=0,fee=0;
            for(var ticket:group){amount=Math.addExact(amount,ticket.remainingTotalMinor());
                fee=Math.addExact(fee,ticket.remainingFeeMinor());}
            String key="event-cancel-"+eventId+"-"+entry.getKey();
            ids.add(repository.createRefund(entry.getKey(),first.accountId(),key,
                fingerprint("EVENT_CANCELLATION",entry.getKey(),group.stream().map(AdminRepository.TicketRow::id).toList()),
                "EVENT_CANCELLATION",amount,fee,first.currency(),group,requested,lookup,now));}
        repository.restorePromos(eventId);repository.cancelEvent(eventId,now);
        repository.audit(actor,"EVENT_CANCELLED","EVENT",eventId,reason,event.state(),"CANCELLED",now);
        repository.outbox("EVENT",eventId,"EVENT_CANCELLED",now);return new PreparedCancellation(List.copyOf(ids));
    }

    public ExceptionalResult exceptional(UUID bookingId,List<UUID> requestedIds,String rawKey,String rawReason,
                                         MockPaymentGateway.RequestedOutcome requested,
                                         MockPaymentGateway.LookupOutcome lookup,AuthenticatedAccount actor){
        requireAdmin(actor);String reason=reason(rawReason),key=key(rawKey);List<UUID> ids=ids(requestedIds);
        byte[] fp=fingerprint("EXCEPTIONAL",bookingId,ids);
        PreparedRefund prepared=transactions.execute(status->prepareExceptional(
            bookingId,ids,key,fp,reason,requested,lookup,actor.accountId()));
        if(prepared.replayed())return new ExceptionalResult(prepared.id(),prepared.state(),true);
        var result=refunds.processPrepared(prepared.id(),requested);
        return new ExceptionalResult(result.id(),result.state(),false);
    }

    private PreparedRefund prepareExceptional(UUID bookingId,List<UUID> ids,String key,byte[] fp,String reason,
                                              MockPaymentGateway.RequestedOutcome requested,
                                              MockPaymentGateway.LookupOutcome lookup,UUID actor){
        var booking=repository.booking(bookingId).orElseThrow(ResourceNotFoundException::new);
        var prior=repository.refundByKey(booking.accountId(),key).orElse(null);
        if(prior!=null){verify(prior,fp);return new PreparedRefund(prior.id(),prior.state(),true);}
        repository.lockEvent(booking.eventId()).orElseThrow(ResourceNotFoundException::new);
        prior=repository.refundByKey(booking.accountId(),key).orElse(null);
        if(prior!=null){verify(prior,fp);return new PreparedRefund(prior.id(),prior.state(),true);}
        repository.lockLimit(booking.accountId(),booking.eventId());var initial=repository.selectedTickets(bookingId,ids,false);
        if(initial.size()!=ids.size())throw conflict("TICKETS_NOT_REFUNDABLE","One or more tickets have no refundable balance.");
        repository.lockSeats(initial.stream().map(AdminRepository.TicketRow::eventSeatId).sorted().toList());
        repository.lockBooking(bookingId);var tickets=repository.selectedTickets(bookingId,ids,true);
        if(tickets.size()!=ids.size())throw conflict("TICKETS_NOT_REFUNDABLE","One or more tickets have no refundable balance.");
        long amount=0,fee=0;for(var ticket:tickets){amount=Math.addExact(amount,ticket.remainingTotalMinor());
            fee=Math.addExact(fee,ticket.remainingFeeMinor());}
        Instant now=clock.instant();UUID id=repository.createRefund(bookingId,booking.accountId(),key,fp,
            "EXCEPTIONAL",amount,fee,booking.currency(),tickets,requested,lookup,now);
        repository.audit(actor,"EXCEPTIONAL_REFUND","BOOKING",bookingId,reason,"NONE",id.toString(),now);
        return new PreparedRefund(id,"REQUESTED",false);
    }

    public List<AdminRepository.AuditRow> audits(int limit,int offset,AuthenticatedAccount actor){
        requireAdmin(actor);return repository.audits(limit,offset);}
    private void mutable(AdminRepository.EventRow event){if(!List.of("DRAFT","PUBLISHED","ON_SALE","SALES_CLOSED").contains(event.state()))
        throw conflict("EVENT_NOT_MUTABLE","The event state does not permit this operation.");}
    private void scheduled(AdminRepository.EventRow event){if(!List.of("PUBLISHED","ON_SALE","SALES_CLOSED").contains(event.state()))
        throw conflict("EVENT_NOT_MUTABLE","The event state does not permit this operation.");}
    private void requireAdmin(AuthenticatedAccount actor){if(actor.role()!=Account.Role.ADMIN)
        throw new HoldRejectedException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.");}
    private String reason(String raw){String value=raw==null?"":raw.strip();if(value.isEmpty()||value.length()>500)
        throw bad("A reason between 1 and 500 characters is required.");return value;}
    private String key(String raw){String value=raw==null?"":raw.strip();if(value.length()<8||value.length()>100)
        throw bad("The idempotency key must contain between 8 and 100 characters.");return value;}
    private List<UUID> ids(List<UUID> value){if(value==null||value.isEmpty()||value.size()>6)throw bad("Between one and six tickets must be selected.");
        var unique=new HashSet<>(value);if(unique.contains(null)||unique.size()!=value.size())throw bad("Ticket identifiers must be unique.");
        var result=new ArrayList<>(unique);result.sort(UUID::compareTo);return List.copyOf(result);}
    private byte[] fingerprint(String kind,UUID owner,List<UUID> ids){try{return MessageDigest.getInstance("SHA-256")
        .digest((kind+"|"+owner+"|"+ids.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private void verify(AdminRepository.RefundRow refund,byte[] fp){if(!MessageDigest.isEqual(refund.fingerprint(),fp))
        throw conflict("IDEMPOTENCY_KEY_REUSED","The idempotency key was already used for a different request.");}
    private HoldRejectedException bad(String message){return new HoldRejectedException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    private HoldRejectedException conflict(String code,String message){return new HoldRejectedException(HttpStatus.CONFLICT,code,message);}

    private record PreparedCancellation(List<UUID> refundIds){}
    private record PreparedRefund(UUID id,String state,boolean replayed){}
    public record SeatResponse(UUID id,String inventoryState){}
    public record RescheduleResponse(UUID id,String state,Instant previousStartsAt,Instant startsAt,
                                     Instant refundWindowEndsAt){}
    public record CancellationResponse(UUID id,String state,List<RefundService.RefundResponse> refunds){}
    public record ExceptionalResult(UUID id,String state,boolean replayed){}
}
