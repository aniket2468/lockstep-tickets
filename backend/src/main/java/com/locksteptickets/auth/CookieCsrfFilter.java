package com.locksteptickets.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import com.locksteptickets.api.ApiError;
import com.locksteptickets.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class CookieCsrfFilter extends OncePerRequestFilter {
    static final String COOKIE_AUTHENTICATED = CookieCsrfFilter.class.getName() + ".authenticated";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SessionCookieService cookies;
    private final ObjectMapper objectMapper;

    CookieCsrfFilter(SessionCookieService cookies, ObjectMapper objectMapper) {
        this.cookies = cookies;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (Boolean.TRUE.equals(request.getAttribute(COOKIE_AUTHENTICATED))
            && !SAFE_METHODS.contains(request.getMethod()) && !validToken(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                "CSRF_TOKEN_INVALID", "The CSRF token is missing or invalid.",
                RequestIdFilter.from(request)));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean validToken(HttpServletRequest request) {
        String cookie = cookies.csrfToken(request);
        String header = request.getHeader(SessionCookieService.CSRF_HEADER);
        if (cookie == null || header == null) {
            return false;
        }
        return MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8));
    }
}
