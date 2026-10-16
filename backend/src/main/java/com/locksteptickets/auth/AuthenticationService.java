package com.locksteptickets.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final AccessSessionRepository sessions;
    private final LoginThrottleRepository throttles;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Duration throttleWindow;
    private final Duration throttleDuration;
    private final int accountMaxFailures;
    private final int ipMaxFailures;
    private final String dummyPasswordHash;

    public AuthenticationService(
        AccountRepository accounts,
        AccessSessionRepository sessions,
        LoginThrottleRepository throttles,
        PasswordEncoder passwordEncoder,
        Clock clock,
        @Value("${lockstep.authentication.session-ttl}") Duration sessionTtl,
        @Value("${lockstep.authentication.throttle-window}") Duration throttleWindow,
        @Value("${lockstep.authentication.throttle-duration}") Duration throttleDuration,
        @Value("${lockstep.authentication.account-max-failures}") int accountMaxFailures,
        @Value("${lockstep.authentication.ip-max-failures}") int ipMaxFailures
    ) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()
            || sessionTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Session TTL must be greater than zero and at most 24 hours");
        }
        this.accounts = accounts;
        this.sessions = sessions;
        this.throttles = throttles;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.throttleWindow = requirePositiveDuration("Throttle window", throttleWindow);
        this.throttleDuration = requirePositiveDuration("Throttle duration", throttleDuration);
        this.accountMaxFailures = requireValidFailureLimit("Account failure limit", accountMaxFailures);
        this.ipMaxFailures = requireValidFailureLimit("IP failure limit", ipMaxFailures);
        this.dummyPasswordHash = passwordEncoder.encode(randomToken());
    }

    public LoginResult login(String email, String password, String clientAddress) {
        Instant now = clock.instant();
        byte[] accountThrottleKey = throttleKey(
            "account", AccountRepository.normalizeEmail(email));
        byte[] ipThrottleKey = throttleKey("ip", clientAddress);
        ensureNotBlocked(accountThrottleKey, ipThrottleKey, now);

        Account account = accounts.findByEmail(email).orElse(null);
        String storedHash = account == null ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);

        if (account == null || !passwordMatches || !account.active()) {
            Instant blockedUntil = later(
                throttles.recordFailure(accountThrottleKey, now, throttleWindow,
                    throttleDuration, accountMaxFailures).orElse(null),
                throttles.recordFailure(ipThrottleKey, now, throttleWindow,
                    throttleDuration, ipMaxFailures).orElse(null));
            if (blockedUntil != null) {
                throw new LoginThrottledException(retryAfterSeconds(now, blockedUntil));
            }
            throw new InvalidCredentialsException();
        }

        throttles.clear(accountThrottleKey);
        Instant createdAt = now;
        Instant expiresAt = createdAt.plus(sessionTtl);
        String token = randomToken();
        sessions.create(TokenDigester.sha256(token), account.id(), createdAt, expiresAt);
        return new LoginResult(token, expiresAt,
            new AuthenticatedAccount(account.id(), account.email(), account.role()));
    }

    private void ensureNotBlocked(byte[] accountKey, byte[] ipKey, Instant now) {
        Instant blockedUntil = later(
            throttles.blockedUntil(accountKey, now).orElse(null),
            throttles.blockedUntil(ipKey, now).orElse(null));
        if (blockedUntil != null) {
            throw new LoginThrottledException(retryAfterSeconds(now, blockedUntil));
        }
    }

    private static byte[] throttleKey(String scope, String value) {
        return TokenDigester.sha256(scope + ':' + value);
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private static long retryAfterSeconds(Instant now, Instant blockedUntil) {
        return Math.max(1, Duration.between(now, blockedUntil).toSeconds());
    }

    private static Duration requirePositiveDuration(String name, Duration value) {
        if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(name + " must be greater than zero and at most one day");
        }
        return value;
    }

    private static int requireValidFailureLimit(String name, int value) {
        if (value < 2 || value > 1000) {
            throw new IllegalArgumentException(name + " must be between 2 and 1000");
        }
        return value;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String token, Instant expiresAt, AuthenticatedAccount account) {
    }

    public static final class InvalidCredentialsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static final class LoginThrottledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        LoginThrottledException(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
