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

    public HoldService(HoldRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public HoldResponse create(UUID eventId, List<UUID> requestedSeatIds, AuthenticatedAccount account) {
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
        if (repository.hasActiveHold(account.accountId(), eventId, now)) {
            throw conflict("ACTIVE_HOLD_EXISTS", "Only one active hold is allowed per event.");
        }
        if (currentTotal + seatIds.size() > CUSTOMER_EVENT_LIMIT) {
            throw conflict("CUSTOMER_EVENT_LIMIT_EXCEEDED", "The customer event ticket limit would be exceeded.");
        }

        List<HoldRepository.SeatForHold> seats = repository.lockSeats(eventId, seatIds);
        if (seats.size() != seatIds.size()
            || seats.stream().anyMatch(seat -> !"AVAILABLE".equals(seat.inventoryState()))
            || repository.anyActivelyHeld(seatIds, now)) {
            throw conflict("SEATS_UNAVAILABLE", "One or more requested seats are unavailable.");
        }

        try {
            UUID holdId = repository.insertHold(eventId, account.accountId(), now, expiresAt);
            repository.insertItems(holdId, eventId, seatIds, now, expiresAt);
            repository.setActiveCount(account.accountId(), eventId, currentTotal + seatIds.size());
            return new HoldResponse(holdId, eventId, "ACTIVE", now, expiresAt, seatIds);
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23P01")) {
                throw conflict("SEATS_UNAVAILABLE", "One or more requested seats are unavailable.");
            }
            throw exception;
        }
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
        List<UUID> seatIds
    ) {}
}
