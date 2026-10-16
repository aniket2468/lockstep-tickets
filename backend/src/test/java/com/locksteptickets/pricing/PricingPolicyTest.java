package com.locksteptickets.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PricingPolicyTest {

    private final PricingPolicy policy = new PricingPolicy();
    private final UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void appliesRulesSequentiallyAndPreservesEveryMinorUnit() {
        var quote = policy.quote(List.of(
            new PricingPolicy.SeatPrice(first, 1001),
            new PricingPolicy.SeatPrice(second, 2003)), 101, 1000, 500,
            new PricingPolicy.Promo(UUID.randomUUID(), PricingPolicy.PromoType.FIXED, 333, false),
            "USD");

        assertThat(quote.subtotalMinor()).isEqualTo(3004);
        assertThat(quote.discountMinor()).isEqualTo(768);
        assertThat(quote.totalMinor()).isEqualTo(2337);
        assertThat(quote.lines()).extracting(PricingPolicy.Line::promoDiscountMinor)
            .containsExactly(111L, 222L);
        assertThat(quote.lines()).extracting(PricingPolicy.Line::serviceFeeMinor)
            .containsExactly(34L, 67L);
        assertThat(quote.lines().stream().mapToLong(PricingPolicy.Line::finalTotalMinor).sum())
            .isEqualTo(quote.totalMinor());
    }

    @Test
    void capsFixedPromoAtSubtotalAndNeverCreatesNegativePrice() {
        var quote = policy.quote(List.of(new PricingPolicy.SeatPrice(first, 50)),
            0, 0, 0,
            new PricingPolicy.Promo(UUID.randomUUID(), PricingPolicy.PromoType.FIXED, 999, false),
            "USD");
        assertThat(quote.totalMinor()).isZero();
        assertThat(quote.lines().getFirst().promoDiscountMinor()).isEqualTo(50);
    }

    @Test
    void zeroWeightsAllocateBySeatId() {
        var allocation = PricingPolicy.allocate(3, List.of(
            new PricingPolicy.Weight(second, 0), new PricingPolicy.Weight(first, 0)));
        assertThat(allocation).containsEntry(first, 2L).containsEntry(second, 1L);
    }

    @Test
    void rejectsExclusivePromoCombinedWithRuleDiscount() {
        var promo = new PricingPolicy.Promo(UUID.randomUUID(),
            PricingPolicy.PromoType.PERCENT, 1000, true);
        assertThatThrownBy(() -> policy.quote(
            List.of(new PricingPolicy.SeatPrice(first, 1000)), 0, 1, 0, promo, "USD"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
