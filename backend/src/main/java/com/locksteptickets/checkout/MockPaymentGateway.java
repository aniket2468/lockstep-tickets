package com.locksteptickets.checkout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway {

    private static final Scenario DEFAULT_SCENARIO =
        new Scenario(RequestedOutcome.SUCCESS, LookupOutcome.UNKNOWN);

    private final Map<String, Scenario> oneShotScenarios = new ConcurrentHashMap<>();

    /**
     * Returns an internal provider scenario for one operation. Browser and API
     * clients cannot select this value. The normal application path succeeds;
     * integration tests can register a one-shot failure by idempotency key.
     */
    public Scenario scenarioFor(String operationKey) {
        Scenario configured = oneShotScenarios.remove(operationKey);
        return configured == null ? DEFAULT_SCENARIO : configured;
    }

    public void simulateOnce(String operationKey, RequestedOutcome requested, LookupOutcome lookup) {
        oneShotScenarios.put(operationKey, new Scenario(requested, lookup));
    }

    public void clearSimulations() {
        oneShotScenarios.clear();
    }

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
    public record Scenario(RequestedOutcome requested, LookupOutcome lookup) {}
}
