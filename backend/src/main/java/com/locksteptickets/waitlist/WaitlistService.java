package com.locksteptickets.waitlist;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.hold.HoldRejectedException;
import com.locksteptickets.pricing.PricingPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WaitlistService {
    static final Duration OFFER_DURATION=Duration.ofMinutes(15);
    static final Duration HOLD_DURATION=Duration.ofMinutes(10);
    static final int CUSTOMER_EVENT_LIMIT=12;

    private final WaitlistRepository repository;
    private final PricingPolicy pricing;
    private final Clock clock;
    private final TransactionTemplate transactions;

    WaitlistService(WaitlistRepository repository,PricingPolicy pricing,Clock clock,
                    TransactionTemplate transactions) {
        this.repository=repository;this.pricing=pricing;this.clock=clock;this.transactions=transactions;
    }

    @Transactional
    public EntryResponse join(UUID eventId,UUID sectionId,int quantity,AuthenticatedAccount actor) {
        requireCustomer(actor);validateQuantity(quantity);Instant now=clock.instant();
        var event=repository.lockEvent(eventId).orElseThrow(ResourceNotFoundException::new);
        requireOnSale(event,now);repository.expireOffers(eventId,now);
        if(sectionId!=null&&!repository.sectionBelongsToVenue(sectionId,event.venueId()))
            throw invalid("The preferred section does not belong to this event venue.");
        if(repository.availableCount(eventId,sectionId,now)>=quantity)
            throw conflict("WAITLIST_NOT_NEEDED","Enough matching seats are currently available.");
        try {
            UUID id=repository.insertEntry(eventId,actor.accountId(),sectionId,quantity,now);
            repository.outbox(id,"WAITLIST_JOINED",now);
            return new EntryResponse(id,eventId,sectionId,quantity,"WAITING",null,null,null,now);
        } catch(DataIntegrityViolationException exception) {
            if(hasSqlState(exception,"23505"))
                throw conflict("ACTIVE_WAITLIST_EXISTS","Only one active waitlist entry is allowed per event.");
            throw exception;
        }
    }

    @Transactional
    public PromotionResponse promote(UUID eventId,String rawReason,AuthenticatedAccount actor) {
        requireAdmin(actor);String reason=reason(rawReason);Instant now=clock.instant();
        var event=repository.lockEvent(eventId).orElseThrow(ResourceNotFoundException::new);
        requireOnSale(event,now);repository.expireOffers(eventId,now);
        for(var candidate:repository.candidates(eventId)) {
            repository.ensureAndLockLimit(candidate.accountId(),eventId);
            int current=repository.refreshAndGetTotal(candidate.accountId(),eventId,now);
            if(current+candidate.quantity()>CUSTOMER_EVENT_LIMIT) {
                var locked=repository.lockEntry(candidate.id());
                if("WAITING".equals(locked.state())) {
                    repository.cancel(candidate.id(),now);
                    repository.outbox(candidate.id(),"WAITLIST_SKIPPED_LIMIT",now);
                }
                continue;
            }
            if(repository.hasNonOfferHold(candidate.accountId(),eventId,now)) continue;
            var seats=repository.lockAvailableSeats(eventId,candidate.preferredSectionId(),candidate.quantity(),now);
            if(seats.size()!=candidate.quantity()) continue;
            var locked=repository.lockEntry(candidate.id());
            if(!"WAITING".equals(locked.state())) continue;
            if(seats.stream().anyMatch(seat->!event.currency().equals(seat.currency())))
                throw conflict("INVENTORY_CURRENCY_MISMATCH","Matching inventory has an invalid currency.");
            int early=event.earlyBirdEndsAt()!=null&&now.isBefore(event.earlyBirdEndsAt())
                ?event.earlyBirdDiscountBps():0;
            int group=event.groupSizeThreshold()!=null&&candidate.quantity()>=event.groupSizeThreshold()
                ?event.groupDiscountBps():0;
            var quote=pricing.quote(seats.stream().map(seat->
                    new PricingPolicy.SeatPrice(seat.id(),seat.priceMinor())).toList(),
                event.serviceFeeMinor(),early,group,null,event.currency());
            Instant expires=now.plus(OFFER_DURATION);
            UUID hold=repository.insertOfferHold(event,candidate.accountId(),now,expires,quote);
            repository.addActiveCount(candidate.accountId(),eventId,candidate.quantity());
            repository.offer(candidate.id(),hold,now,expires);
            repository.audit(actor.accountId(),"WAITLIST_OFFERED",candidate.id(),reason,"WAITING","OFFERED",now);
            repository.outbox(candidate.id(),"WAITLIST_OFFERED",now);
            return new PromotionResponse(candidate.id(),candidate.accountId(),hold,"OFFERED",expires,
                quote.lines().stream().map(PricingPolicy.Line::seatId).toList());
        }
        throw conflict("NO_ELIGIBLE_WAITLIST_ENTRY","No waiting entry can use the available inventory.");
    }

    public ClaimResponse claim(UUID entryId,AuthenticatedAccount actor) {
        requireCustomer(actor);Instant now=clock.instant();
        var visible=repository.findOwned(entryId,actor.accountId()).orElseThrow(ResourceNotFoundException::new);
        transactions.executeWithoutResult(status->{repository.lockEvent(visible.eventId())
            .orElseThrow(ResourceNotFoundException::new);repository.expireOffers(visible.eventId(),now);});
        return transactions.execute(status->claimActive(entryId,actor,now));
    }

    private ClaimResponse claimActive(UUID entryId,AuthenticatedAccount actor,Instant now) {
        var visible=repository.findOwned(entryId,actor.accountId()).orElseThrow(ResourceNotFoundException::new);
        var event=repository.lockEvent(visible.eventId()).orElseThrow(ResourceNotFoundException::new);
        requireOnSale(event,now);
        if(!"OFFERED".equals(visible.state())||visible.offerHoldId()==null)
            throw conflict("WAITLIST_OFFER_EXPIRED","The waitlist offer is no longer active.");
        repository.ensureAndLockLimit(actor.accountId(),event.id());
        repository.refreshAndGetTotal(actor.accountId(),event.id(),now);
        var seats=repository.lockOfferSeats(visible.offerHoldId());
        var offer=repository.lockOffer(visible.offerHoldId(),actor.accountId());
        var entry=repository.lockEntry(entryId);
        if(!"OFFERED".equals(entry.state())||entry.offerExpiresAt()==null||!now.isBefore(entry.offerExpiresAt()))
            throw conflict("WAITLIST_OFFER_EXPIRED","The waitlist offer is no longer active.");
        if(seats.size()!=entry.quantity()||offer.lines().size()!=entry.quantity())
            throw conflict("WAITLIST_OFFER_INVALID","The waitlist offer inventory is incomplete.");
        repository.endOffer(entry.offerHoldId(),now,"RELEASED");
        Instant expires=now.plus(HOLD_DURATION);
        UUID hold=repository.insertActiveHold(event,actor.accountId(),now,expires,offer);
        repository.claim(entryId,hold,now);repository.outbox(entryId,"WAITLIST_CLAIMED",now);
        return new ClaimResponse(entryId,hold,event.id(),"ACTIVE",now,expires,seats,
            offer.currency(),offer.subtotalMinor(),offer.discountMinor(),offer.serviceFeeMinor(),offer.totalMinor());
    }

    @Transactional
    public EntryResponse cancel(UUID entryId,AuthenticatedAccount actor) {
        requireCustomer(actor);Instant now=clock.instant();
        var visible=repository.findOwned(entryId,actor.accountId()).orElseThrow(ResourceNotFoundException::new);
        repository.lockEvent(visible.eventId()).orElseThrow(ResourceNotFoundException::new);
        repository.expireOffers(visible.eventId(),now);
        visible=repository.findOwned(entryId,actor.accountId()).orElseThrow(ResourceNotFoundException::new);
        if("WAITING".equals(visible.state())) {
            var entry=repository.lockEntry(entryId);
            if(!"WAITING".equals(entry.state())) throw conflict("WAITLIST_NOT_ACTIVE","The entry is no longer active.");
            repository.cancel(entryId,now);repository.outbox(entryId,"WAITLIST_CANCELLED",now);
            return response(entry,"CANCELLED",now);
        }
        if(!"OFFERED".equals(visible.state())||visible.offerHoldId()==null)
            throw conflict("WAITLIST_NOT_ACTIVE","The entry is no longer active.");
        repository.ensureAndLockLimit(actor.accountId(),visible.eventId());
        repository.lockOfferSeats(visible.offerHoldId());repository.lockOffer(visible.offerHoldId(),actor.accountId());
        var entry=repository.lockEntry(entryId);
        if(!"OFFERED".equals(entry.state())) throw conflict("WAITLIST_NOT_ACTIVE","The entry is no longer active.");
        repository.endOffer(entry.offerHoldId(),now,"RELEASED");repository.cancel(entryId,now);
        repository.refreshAndGetTotal(actor.accountId(),visible.eventId(),now);
        repository.outbox(entryId,"WAITLIST_CANCELLED",now);return response(entry,"CANCELLED",now);
    }

    public List<EntryResponse> list(AuthenticatedAccount actor) {
        requireCustomer(actor);Instant now=clock.instant();return repository.owned(actor.accountId()).stream()
            .map(entry->response(entry,"OFFERED".equals(entry.state())&&entry.offerExpiresAt()!=null
                &&!now.isBefore(entry.offerExpiresAt())?"EXPIRED":entry.state(),null)).toList();
    }

    private EntryResponse response(WaitlistRepository.EntryRow entry,String state,Instant resolved) {
        return new EntryResponse(entry.id(),entry.eventId(),entry.preferredSectionId(),entry.quantity(),state,
            entry.offerHoldId(),entry.claimedHoldId(),entry.offerExpiresAt(),entry.createdAt());
    }
    private void requireOnSale(WaitlistRepository.EventRow event,Instant now) {
        if(!"ON_SALE".equals(event.state())||now.isBefore(event.salesStartAt())||!now.isBefore(event.salesEndAt()))
            throw conflict("EVENT_NOT_ON_SALE","The event is not currently on sale.");
    }
    private void validateQuantity(int value) {if(value<1||value>6)throw invalid("Quantity must be between one and six.");}
    private String reason(String raw) {String value=raw==null?"":raw.strip();
        if(value.isEmpty()||value.length()>500)throw invalid("A reason between 1 and 500 characters is required.");return value;}
    private boolean hasSqlState(Throwable exception,String expected){Throwable current=exception;
        while(current!=null){if(current instanceof SQLException sql&&expected.equals(sql.getSQLState()))return true;
            current=current.getCause();}return false;}
    private void requireCustomer(AuthenticatedAccount actor) {if(actor.role()!=Account.Role.CUSTOMER)
        throw new HoldRejectedException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.");}
    private void requireAdmin(AuthenticatedAccount actor) {if(actor.role()!=Account.Role.ADMIN)
        throw new HoldRejectedException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.");}
    private HoldRejectedException invalid(String message) {return new HoldRejectedException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    private HoldRejectedException conflict(String code,String message) {return new HoldRejectedException(HttpStatus.CONFLICT,code,message);}

    public record EntryResponse(UUID id,UUID eventId,UUID preferredSectionId,int quantity,String state,
                                UUID offerHoldId,UUID claimedHoldId,Instant offerExpiresAt,Instant createdAt){}
    public record PromotionResponse(UUID entryId,UUID accountId,UUID offerHoldId,String state,
                                    Instant expiresAt,List<UUID> seatIds){}
    public record ClaimResponse(UUID entryId,UUID holdId,UUID eventId,String state,Instant createdAt,
                                Instant expiresAt,List<UUID> seatIds,String currency,long subtotalMinor,
                                long discountMinor,long serviceFeeMinor,long totalMinor){}
}
