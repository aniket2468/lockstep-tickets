package com.locksteptickets.checkout;

import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway {

    public ObservedOutcome charge(RequestedOutcome requested) {
        return switch (requested) {
            case SUCCESS -> ObservedOutcome.SUCCESS;
            case DECLINE -> ObservedOutcome.DECLINE;
            case TIMEOUT -> ObservedOutcome.TIMEOUT;
            case UNKNOWN -> ObservedOutcome.UNKNOWN;
        };
    }

    public LookupOutcome lookup(LookupOutcome configured) {
        return configured;
    }

    public enum RequestedOutcome { SUCCESS, DECLINE, TIMEOUT, UNKNOWN }
    public enum ObservedOutcome { SUCCESS, DECLINE, TIMEOUT, UNKNOWN }
    public enum LookupOutcome { SUCCESS, DECLINE, UNKNOWN }
}
