package com.locksteptickets.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookieService {
    static final String SESSION_COOKIE = "lockstep_session";
    static final String CSRF_COOKIE = "lockstep_csrf";
    static final String CSRF_HEADER = "X-CSRF-Token";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final boolean secure;

    SessionCookieService(@Value("${lockstep.authentication.cookie-secure}") boolean secure) {
        this.secure = secure;
    }

    String sessionToken(HttpServletRequest request) {
        return cookie(request, SESSION_COOKIE);
    }

    String authenticationToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return sessionToken(request);
    }

    String csrfToken(HttpServletRequest request) {
        return cookie(request, CSRF_COOKIE);
    }

    String newCsrfToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    ResponseCookie sessionCookie(String token, Duration maxAge) {
        return cookieBuilder(SESSION_COOKIE, token).httpOnly(true).maxAge(maxAge).build();
    }

    ResponseCookie csrfCookie(String token, Duration maxAge) {
        return cookieBuilder(CSRF_COOKIE, token).httpOnly(false).maxAge(maxAge).build();
    }

    ResponseCookie clearSessionCookie() {
        return cookieBuilder(SESSION_COOKIE, "").httpOnly(true).maxAge(Duration.ZERO).build();
    }

    ResponseCookie clearCsrfCookie() {
        return cookieBuilder(CSRF_COOKIE, "").httpOnly(false).maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String name, String value) {
        return ResponseCookie.from(name, value)
            .secure(secure)
            .sameSite("Strict")
            .path("/");
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
            .filter(cookie -> name.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }
}
