package com.locksteptickets.refund;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class RefundPolicy {
    static final Duration FULL_WINDOW = Duration.ofDays(7);
    static final Duration HALF_WINDOW = Duration.ofHours(48);

    public int customerPercentageBps(Instant now, Instant eventStartsAt) {
        Duration remaining = Duration.between(now, eventStartsAt);
        if (!remaining.isNegative() && remaining.compareTo(FULL_WINDOW) >= 0) {
            return 10_000;
        }
        if (!remaining.isNegative() && remaining.compareTo(HALF_WINDOW) >= 0) {
            return 5_000;
        }
        return 0;
    }

    public long amount(long chargedTotalMinor, long serviceFeeMinor, int percentageBps) {
        long refundable = Math.subtractExact(chargedTotalMinor, serviceFeeMinor);
        return Math.multiplyExact(refundable, percentageBps) / 10_000;
    }
}
