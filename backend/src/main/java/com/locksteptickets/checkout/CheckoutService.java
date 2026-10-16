package com.locksteptickets.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.hold.HoldRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckoutService {

    private final CheckoutRepository repository;
    private final MockPaymentGateway gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CheckoutService(CheckoutRepository repository, MockPaymentGateway gateway,
                    TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.transactions = transactions;
        this.clock = clock;
    }

    public CheckoutResult checkout(UUID holdId, String key,
                                   MockPaymentGateway.RequestedOutcome requested,
                                   MockPaymentGateway.LookupOutcome lookup,
                                   AuthenticatedAccount account) {
        requireCustomer(account);
        String normalizedKey = key.strip();
        if (normalizedKey.length() < 8 || normalizedKey.length() > 100) {
            throw new HoldRejectedException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The idempotency key must contain between 8 and 100 characters.");
        }
        byte[] fingerprint = fingerprint(holdId, requested, lookup);
        Prepared prepared = transactions.execute(status -> prepare(
            holdId, normalizedKey, fingerprint, requested, lookup, account.accountId()));
        if (prepared.replayed()) {
            return new CheckoutResult(response(resume(prepared.booking())), true);
        }

        MockPaymentGateway.ObservedOutcome observed = gateway.charge(requested);
        transactions.executeWithoutResult(status ->
            repository.recordObserved(prepared.booking().id(), observed, clock.instant()));
        MockPaymentGateway.ObservedOutcome durable = observedFromPaymentState(
            repository.paymentState(prepared.booking().id()));
        CheckoutRepository.BookingRow completed = transactions.execute(status ->
            finalizeOutcome(prepared.booking().id(), durable));
        return new CheckoutResult(response(completed), false);
    }

    public BookingResponse reconcile(UUID bookingId, AuthenticatedAccount account) {
        requireCustomer(account);
        CheckoutRepository.BookingRow visible = repository.findOwnedBooking(bookingId, account.accountId())
            .orElseThrow(ResourceNotFoundException::new);
        if ("CONFIRMED".equals(visible.state()) || "PAYMENT_FAILED".equals(visible.state())) {
            return response(visible);
        }
        String paymentState = repository.paymentState(bookingId);
        if ("SUCCEEDED".equals(paymentState)) {
            return response(transactions.execute(status -> finalizeOutcome(
                bookingId, MockPaymentGateway.ObservedOutcome.SUCCESS)));
        }
        if ("DECLINED".equals(paymentState)) {
            return response(transactions.execute(status -> finalizeOutcome(
                bookingId, MockPaymentGateway.ObservedOutcome.DECLINE)));
        }
        MockPaymentGateway.LookupOutcome configured = repository.lookupOutcome(bookingId);
        MockPaymentGateway.LookupOutcome outcome = gateway.lookup(configured);
        transactions.executeWithoutResult(status ->
            repository.recordLookup(bookingId, outcome, clock.instant()));
        return response(transactions.execute(status -> reconcileOutcome(bookingId, outcome)));
    }

    private Prepared prepare(UUID holdId, String key, byte[] fingerprint,
                             MockPaymentGateway.RequestedOutcome requested,
                             MockPaymentGateway.LookupOutcome lookup, UUID accountId) {
        CheckoutRepository.BookingRow prior = repository.findByKey(accountId, key).orElse(null);
        if (prior != null) {
            verifyFingerprint(prior, fingerprint);
            return new Prepared(prior, true);
        }
        CheckoutRepository.HoldRow initial = repository.findOwnedHold(holdId, accountId)
            .orElseThrow(ResourceNotFoundException::new);
        repository.lockEvent(initial.eventId());
        prior = repository.findByKey(accountId, key).orElse(null);
        if (prior != null) {
            verifyFingerprint(prior, fingerprint);
            return new Prepared(prior, true);
        }
        repository.lockLimit(accountId, initial.eventId());
        var items = repository.lockItems(holdId);
        if (initial.promoId() != null) {
            repository.lockPromo(initial.promoId());
        }
        CheckoutRepository.HoldRow hold = repository.lockHold(holdId, accountId);
        Instant now = clock.instant();
        if (!"ACTIVE".equals(hold.state()) || !now.isBefore(hold.expiresAt())) {
            throw conflict("HOLD_NOT_ACTIVE", "The hold is expired or no longer active.");
        }
        if (items.isEmpty() || hold.currency() == null || hold.subtotalMinor() == null
            || items.stream().anyMatch(item -> item.currency() == null
                || item.basePriceMinor() == null || item.finalTotalMinor() == null)) {
            throw conflict("HOLD_QUOTE_INVALID", "The immutable hold quote is incomplete.");
        }
        validateQuote(hold, items);
        if (hold.promoId() != null && !repository.promoHasCapacity(hold.promoId(), accountId)) {
            throw conflict("PROMO_LIMIT_REACHED", "The promo code usage limit has been reached.");
        }
        UUID bookingId = repository.createBooking(hold, key, fingerprint, now);
        repository.createAttempt(bookingId, requested, lookup, hold.totalMinor(), hold.currency(), now);
        if (hold.promoId() != null) {
            repository.reservePromo(hold.promoId(), accountId, bookingId, now);
        }
        repository.markPaymentPending(holdId);
        return new Prepared(repository.findOwnedBooking(bookingId, accountId).orElseThrow(), false);
    }

    private CheckoutRepository.BookingRow finalizeOutcome(
        UUID bookingId, MockPaymentGateway.ObservedOutcome outcome
    ) {
        CheckoutRepository.BookingRow initial = repository.findOwnedBooking(
            bookingId, findAccount(bookingId)).orElseThrow();
        return lockAndApply(initial, outcome);
    }

    private CheckoutRepository.BookingRow reconcileOutcome(
        UUID bookingId, MockPaymentGateway.LookupOutcome outcome
    ) {
        CheckoutRepository.BookingRow initial = repository.findOwnedBooking(bookingId, findAccount(bookingId))
            .orElseThrow();
        MockPaymentGateway.ObservedOutcome observed = switch (outcome) {
            case SUCCESS -> MockPaymentGateway.ObservedOutcome.SUCCESS;
            case DECLINE -> MockPaymentGateway.ObservedOutcome.DECLINE;
            case UNKNOWN -> MockPaymentGateway.ObservedOutcome.UNKNOWN;
        };
        return lockAndApply(initial, observed);
    }

    private CheckoutRepository.BookingRow lockAndApply(
        CheckoutRepository.BookingRow initial, MockPaymentGateway.ObservedOutcome outcome
    ) {
        repository.lockEvent(initial.eventId());
        repository.lockLimit(initial.accountId(), initial.eventId());
        var items = repository.lockItems(initial.holdId());
        CheckoutRepository.HoldRow hold = repository.findOwnedHold(initial.holdId(), initial.accountId())
            .orElseThrow();
        if (hold.promoId() != null) {
            repository.lockPromo(hold.promoId());
        }
        CheckoutRepository.BookingRow booking = repository.lockBooking(initial.id());
        if ("CONFIRMED".equals(booking.state()) || "PAYMENT_FAILED".equals(booking.state())) {
            return booking;
        }
        Instant now = clock.instant();
        switch (outcome) {
            case SUCCESS -> repository.confirm(booking, items, now);
            case DECLINE -> repository.fail(booking, items.size(), now);
            case TIMEOUT, UNKNOWN -> repository.markUnknown(booking.id(), now);
        }
        return repository.findOwnedBooking(booking.id(), booking.accountId()).orElseThrow();
    }

    private CheckoutRepository.BookingRow resume(CheckoutRepository.BookingRow booking) {
        if ("CONFIRMED".equals(booking.state()) || "PAYMENT_FAILED".equals(booking.state())) {
            return booking;
        }
        return switch (repository.paymentState(booking.id())) {
            case "SUCCEEDED" -> transactions.execute(status -> finalizeOutcome(
                booking.id(), MockPaymentGateway.ObservedOutcome.SUCCESS));
            case "DECLINED" -> transactions.execute(status -> finalizeOutcome(
                booking.id(), MockPaymentGateway.ObservedOutcome.DECLINE));
            default -> repository.findOwnedBooking(booking.id(), booking.accountId()).orElseThrow();
        };
    }

    private MockPaymentGateway.ObservedOutcome observedFromPaymentState(String state) {
        return switch (state) {
            case "SUCCEEDED" -> MockPaymentGateway.ObservedOutcome.SUCCESS;
            case "DECLINED" -> MockPaymentGateway.ObservedOutcome.DECLINE;
            default -> MockPaymentGateway.ObservedOutcome.UNKNOWN;
        };
    }

    private void validateQuote(CheckoutRepository.HoldRow hold,
                               java.util.List<CheckoutRepository.HoldItem> items) {
        try {
            long subtotal = 0, discount = 0, fee = 0, total = 0;
            for (CheckoutRepository.HoldItem item : items) {
                if (!hold.currency().equals(item.currency()) || item.earlyDiscountMinor() == null
                    || item.groupDiscountMinor() == null || item.promoDiscountMinor() == null
                    || item.serviceFeeMinor() == null) {
                    throw new ArithmeticException();
                }
                subtotal = Math.addExact(subtotal, item.basePriceMinor());
                discount = Math.addExact(discount, Math.addExact(item.earlyDiscountMinor(),
                    Math.addExact(item.groupDiscountMinor(), item.promoDiscountMinor())));
                fee = Math.addExact(fee, item.serviceFeeMinor());
                total = Math.addExact(total, item.finalTotalMinor());
            }
            if (subtotal != hold.subtotalMinor() || discount != hold.discountMinor()
                || fee != hold.serviceFeeMinor() || total != hold.totalMinor()) {
                throw new ArithmeticException();
            }
        } catch (ArithmeticException exception) {
            throw conflict("HOLD_QUOTE_INVALID", "The immutable hold quote is inconsistent.");
        }
    }

    private UUID findAccount(UUID bookingId) {
        return repository.accountIdForBooking(bookingId).orElseThrow(ResourceNotFoundException::new);
    }

    private void verifyFingerprint(CheckoutRepository.BookingRow booking, byte[] expected) {
        if (!MessageDigest.isEqual(booking.fingerprint(), expected)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED",
                "The idempotency key was already used for a different request.");
        }
    }

    private byte[] fingerprint(UUID holdId, MockPaymentGateway.RequestedOutcome requested,
                               MockPaymentGateway.LookupOutcome lookup) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                (holdId + "|" + requested + "|" + lookup).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void requireCustomer(AuthenticatedAccount account) {
        if (account.role() != Account.Role.CUSTOMER) {
            throw new HoldRejectedException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.");
        }
    }

    private HoldRejectedException conflict(String code, String message) {
        return new HoldRejectedException(HttpStatus.CONFLICT, code, message);
    }

    private BookingResponse response(CheckoutRepository.BookingRow booking) {
        return new BookingResponse(booking.id(), booking.reference(), booking.holdId(), booking.state(),
            booking.currency(), booking.subtotalMinor(), booking.discountMinor(),
            booking.serviceFeeMinor(), booking.totalMinor());
    }

    private record Prepared(CheckoutRepository.BookingRow booking, boolean replayed) {}
    public record CheckoutResult(BookingResponse booking, boolean replayed) {}
    public record BookingResponse(UUID id, String reference, UUID holdId, String state,
                                  String currency, long subtotalMinor, long discountMinor,
                                  long serviceFeeMinor, long totalMinor) {}
}
