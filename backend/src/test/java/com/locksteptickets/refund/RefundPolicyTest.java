package com.locksteptickets.refund;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {
    private final RefundPolicy policy=new RefundPolicy();
    private final Instant now=Instant.parse("2030-03-08T12:00:00Z");

    @Test void equalityGetsMoreFavorableBand(){
        assertThat(policy.customerPercentageBps(now,now.plusSeconds(7*24*3600))).isEqualTo(10_000);
        assertThat(policy.customerPercentageBps(now,now.plusSeconds(7*24*3600-1))).isEqualTo(5_000);
        assertThat(policy.customerPercentageBps(now,now.plusSeconds(48*3600))).isEqualTo(5_000);
        assertThat(policy.customerPercentageBps(now,now.plusSeconds(48*3600-1))).isZero();
    }
    @Test void usesPreciseInstantsAcrossDstAndExcludesFee(){
        // The interval crosses the US spring-forward transition; elapsed instants still decide the band.
        assertThat(policy.customerPercentageBps(now,now.plusSeconds(7*24*3600))).isEqualTo(10_000);
        assertThat(policy.amount(1101,101,5_000)).isEqualTo(500);
    }
}
