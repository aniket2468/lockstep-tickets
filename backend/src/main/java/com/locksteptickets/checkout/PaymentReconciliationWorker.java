package com.locksteptickets.checkout;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "lockstep.payment-reconciliation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PaymentReconciliationWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationWorker.class);

    private final CheckoutRepository repository;
    private final CheckoutService service;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batchSize;

    PaymentReconciliationWorker(
        CheckoutRepository repository,
        CheckoutService service,
        Clock clock,
        @Value("${lockstep.payment-reconciliation.stale-after:PT30S}") Duration staleAfter,
        @Value("${lockstep.payment-reconciliation.batch-size:50}") int batchSize
    ) {
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("Payment reconciliation stale-after must be positive.");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Payment reconciliation batch-size must be between 1 and 500.");
        }
        this.repository = repository;
        this.service = service;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${lockstep.payment-reconciliation.interval:PT30S}",
        initialDelayString = "${lockstep.payment-reconciliation.initial-delay:PT30S}"
    )
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        var candidates = repository.findReconciliationCandidates(
            clock.instant().minus(staleAfter), batchSize);
        int resolved = 0;
        int stillPending = 0;
        int failed = 0;
        for (var bookingId : candidates) {
            try {
                String state = service.reconcilePending(bookingId).state();
                if ("PAYMENT_PENDING".equals(state) || "PAYMENT_UNKNOWN".equals(state)) {
                    stillPending++;
                } else {
                    resolved++;
                }
            } catch (RuntimeException exception) {
                failed++;
                LOG.warn("Payment reconciliation failed for booking {}", bookingId, exception);
            }
        }
        return new RunResult(candidates.size(), resolved, stillPending, failed);
    }

    public record RunResult(int selected, int resolved, int stillPending, int failed) {}
}
