package com.locksteptickets.hold;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.pricing.PricingPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {

    static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    static final int CUSTOMER_EVENT_LIMIT = 12;

    private final HoldRepository repository;
    private final Clock clock;
    private final PricingPolicy pricingPolicy;

    public HoldService(HoldRepository repository, Clock clock, PricingPolicy pricingPolicy) {
        this.repository = repository;
        this.clock = clock;
        this.pricingPolicy = pricingPolicy;
    }

    @Transactional
    public HoldResponse create(UUID eventId, List<UUID> requestedSeatIds, String requestedPromoCode,
                               AuthenticatedAccount account) {
        if (account.role() != Account.Role.CUSTOMER) {
            throw new HoldRejectedException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.");
        }
        List<UUID> seatIds = validateSeatIds(requestedSeatIds);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(HOLD_DURATION);

        HoldRepository.EventForHold event = repository.lockVisibleEvent(eventId)
            .orElseThrow(ResourceNotFoundException::new);
        if (!"ON_SALE".equals(event.state())
            || now.isBefore(event.salesStartAt()) || !now.isBefore(event.salesEndAt())) {
            throw conflict("EVENT_NOT_ON_SALE", "The event is not currently on sale.");
        }

        repository.expireEventHolds(eventId, now);
        repository.ensureAndLockLimit(account.accountId(), eventId);
        int currentTotal = repository.refreshAndGetTotal(account.accountId(), eventId, now);
        if (currentTotal + seatIds.size() > CUSTOMER_EVENT_LIMIT) {
            throw conflict("CUSTOMER_EVENT_LIMIT_EXCEEDED", "The customer event ticket limit would be exceeded.");
        }
        if (repository.hasActiveHold(account.accountId(), eventId, now)) {
            throw conflict("ACTIVE_HOLD_EXISTS", "Only one active hold is allowed per event.");
        }

        List<HoldRepository.SeatForHold> seats = repository.lockSeats(eventId, seatIds);
        if (seats.size() != seatIds.size()
            || seats.stream().anyMatch(seat -> !"AVAILABLE".equals(seat.inventoryState()))
            || seats.stream().anyMatch(seat -> !event.currency().equals(seat.currency()))
            || repository.anyActivelyHeld(seatIds, now)) {
            throw conflict("SEATS_UNAVAILABLE", "One or more requested seats are unavailable.");
        }

        String promoCode = normalizePromo(requestedPromoCode);
        HoldRepository.PromoForHold promo = promoCode == null ? null
            : repository.lockPromo(promoCode, eventId, account.accountId(), now)
                .orElseThrow(() -> conflict("PROMO_INVALID", "The promo code is not valid."));
        if (promo != null && ((promo.globalLimit() != null
                && promo.redeemedCount() >= promo.globalLimit())
            || (promo.perCustomerLimit() != null
                && promo.customerUses() >= promo.perCustomerLimit()))) {
            throw conflict("PROMO_LIMIT_REACHED", "The promo code usage limit has been reached.");
        }
        int earlyBps = event.earlyBirdEndsAt() != null && now.isBefore(event.earlyBirdEndsAt())
            ? event.earlyBirdDiscountBps() : 0;
        int groupBps = event.groupSizeThreshold() != null
            && seatIds.size() >= event.groupSizeThreshold() ? event.groupDiscountBps() : 0;
        PricingPolicy.Quote quote;
        try {
            quote = pricingPolicy.quote(seats.stream()
                    .map(seat -> new PricingPolicy.SeatPrice(seat.id(), seat.priceMinor())).toList(),
                event.serviceFeeMinor(), earlyBps, groupBps,
                promo == null ? null : new PricingPolicy.Promo(
                    promo.id(), promo.type(), promo.value(), promo.exclusive()), event.currency());
        } catch (IllegalArgumentException exception) {
            throw conflict("PROMO_NOT_COMBINABLE", exception.getMessage());
        }

        try {
            UUID holdId = repository.insertHold(eventId, account.accountId(),
                promo == null ? null : promo.id(), now, expiresAt, quote);
            repository.insertItems(holdId, eventId, quote, now, expiresAt);
            repository.addActiveCount(account.accountId(), eventId, seatIds.size());
            return new HoldResponse(holdId, eventId, "ACTIVE", now, expiresAt, seatIds,
                promoCode, quote.currency(), quote.subtotalMinor(), quote.discountMinor(),
                quote.serviceFeeMinor(), quote.totalMinor(), quote.lines());
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23P01")) {
                throw conflict("SEATS_UNAVAILABLE", "One or more requested seats are unavailable.");
            }
            throw exception;
        }
    }

    @Transactional
    public List<HoldResponse> listActive(AuthenticatedAccount account) {
        if (account.role() != Account.Role.CUSTOMER) {
            throw new HoldRejectedException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.");
        }
        Instant now = clock.instant();
        repository.expireOwnedHolds(account.accountId(), now);
        return repository.findActiveHolds(account.accountId(), now).stream()
            .map(hold -> new HoldResponse(
                hold.id(), hold.eventId(), hold.state(), hold.createdAt(), hold.expiresAt(),
                hold.seatIds(), hold.promoCode(), hold.currency(), hold.subtotalMinor(),
                hold.discountMinor(), hold.serviceFeeMinor(), hold.totalMinor(), List.of()))
            .toList();
    }

    private String normalizePromo(String requestedPromoCode) {
        if (requestedPromoCode == null || requestedPromoCode.isBlank()) {
            return null;
        }
        return requestedPromoCode.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean hasSqlState(Throwable exception, String expected) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                && expected.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<UUID> validateSeatIds(List<UUID> requestedSeatIds) {
        if (requestedSeatIds == null || requestedSeatIds.isEmpty() || requestedSeatIds.size() > 6) {
            throw invalid("Between one and six seats must be requested.");
        }
        var unique = new HashSet<>(requestedSeatIds);
        if (unique.contains(null) || unique.size() != requestedSeatIds.size()) {
            throw invalid("Seat identifiers must be non-null and unique.");
        }
        var ordered = new ArrayList<>(unique);
        ordered.sort(UUID::compareTo);
        return List.copyOf(ordered);
    }

    private HoldRejectedException invalid(String message) {
        return new HoldRejectedException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private HoldRejectedException conflict(String code, String message) {
        return new HoldRejectedException(HttpStatus.CONFLICT, code, message);
    }

    public record HoldResponse(
        UUID id,
        UUID eventId,
        String state,
        Instant createdAt,
        Instant expiresAt,
        List<UUID> seatIds,
        String promoCode,
        String currency,
        long subtotalMinor,
        long discountMinor,
        long serviceFeeMinor,
        long totalMinor,
        List<PricingPolicy.Line> lines
    ) {}
}
