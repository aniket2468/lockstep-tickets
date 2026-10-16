package com.locksteptickets.pricing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class PricingPolicy {

    public Quote quote(List<SeatPrice> seats, long serviceFeeMinor, int earlyBps,
                       int groupBps, Promo promo, String currency) {
        if (seats.isEmpty() || serviceFeeMinor < 0) {
            throw new IllegalArgumentException("Invalid pricing inputs.");
        }
        boolean earlyApplied = earlyBps > 0;
        boolean groupApplied = groupBps > 0;
        if (promo != null && promo.exclusive() && (earlyApplied || groupApplied)) {
            throw new IllegalArgumentException("An exclusive promo cannot be combined with other discounts.");
        }

        var working = new ArrayList<Working>();
        for (SeatPrice seat : seats.stream().sorted(Comparator.comparing(SeatPrice::seatId)).toList()) {
            long early = percentage(seat.basePriceMinor(), earlyBps);
            long afterEarly = seat.basePriceMinor() - early;
            long group = percentage(afterEarly, groupBps);
            working.add(new Working(seat.seatId(), seat.basePriceMinor(), early, group));
        }
        long eligible = working.stream().mapToLong(Working::afterRules).sum();
        long promoTotal = promo == null ? 0 : promo.type() == PromoType.PERCENT
            ? percentage(eligible, Math.toIntExact(promo.value()))
            : Math.min(promo.value(), eligible);
        Map<UUID, Long> promoParts = allocate(promoTotal, working.stream()
            .map(w -> new Weight(w.seatId(), w.afterRules())).toList());
        Map<UUID, Long> feeParts = allocate(serviceFeeMinor, working.stream()
            .map(w -> new Weight(w.seatId(), w.afterRules() - promoParts.get(w.seatId()))).toList());

        List<Line> lines = working.stream().map(w -> {
            long promoPart = promoParts.get(w.seatId());
            long feePart = feeParts.get(w.seatId());
            return new Line(w.seatId(), w.base(), w.early(), w.group(), promoPart, feePart,
                w.afterRules() - promoPart + feePart);
        }).toList();
        long subtotal = lines.stream().mapToLong(Line::basePriceMinor).sum();
        long discounts = lines.stream().mapToLong(line -> line.earlyDiscountMinor()
            + line.groupDiscountMinor() + line.promoDiscountMinor()).sum();
        return new Quote(currency, subtotal, discounts, serviceFeeMinor,
            subtotal - discounts + serviceFeeMinor, lines);
    }

    static Map<UUID, Long> allocate(long amount, List<Weight> weights) {
        if (amount < 0 || weights.isEmpty()) {
            throw new IllegalArgumentException("Invalid allocation inputs.");
        }
        long sum = weights.stream().mapToLong(Weight::amount).sum();
        boolean equal = sum == 0;
        long denominator = equal ? weights.size() : sum;
        var result = new HashMap<UUID, Long>();
        var remainders = new ArrayList<Remainder>();
        long assigned = 0;
        for (Weight weight : weights) {
            long numeratorWeight = equal ? 1 : weight.amount();
            long base = Math.multiplyExact(amount, numeratorWeight) / denominator;
            long remainder = Math.multiplyExact(amount, numeratorWeight) % denominator;
            result.put(weight.seatId(), base);
            assigned += base;
            remainders.add(new Remainder(weight.seatId(), remainder));
        }
        remainders.sort(Comparator.comparingLong(Remainder::value).reversed()
            .thenComparing(Remainder::seatId));
        for (int index = 0; index < amount - assigned; index++) {
            UUID seatId = remainders.get(index).seatId();
            result.put(seatId, result.get(seatId) + 1);
        }
        return Map.copyOf(result);
    }

    private static long percentage(long amount, int basisPoints) {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("Invalid basis points.");
        }
        return Math.multiplyExact(amount, basisPoints) / 10_000;
    }

    public enum PromoType { PERCENT, FIXED }
    public record Promo(UUID id, PromoType type, long value, boolean exclusive) {}
    public record SeatPrice(UUID seatId, long basePriceMinor) {}
    public record Weight(UUID seatId, long amount) {}
    private record Remainder(UUID seatId, long value) {}
    private record Working(UUID seatId, long base, long early, long group) {
        long afterRules() { return base - early - group; }
    }
    public record Line(UUID seatId, long basePriceMinor, long earlyDiscountMinor,
                       long groupDiscountMinor, long promoDiscountMinor,
                       long serviceFeeMinor, long finalTotalMinor) {}
    public record Quote(String currency, long subtotalMinor, long discountMinor,
                        long serviceFeeMinor, long totalMinor, List<Line> lines) {}
}
