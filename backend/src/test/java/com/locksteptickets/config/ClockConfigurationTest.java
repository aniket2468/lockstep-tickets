package com.locksteptickets.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ClockConfigurationTest {

    @Test
    void applicationClockUsesUtc() {
        var clock = new ClockConfiguration().applicationClock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}

