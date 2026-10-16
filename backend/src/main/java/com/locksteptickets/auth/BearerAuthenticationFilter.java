package com.locksteptickets.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final AccessSessionRepository sessions;
    private final Clock clock;

    public BearerAuthenticationFilter(AccessSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            if (TOKEN_FORMAT.matcher(token).matches()) {
                sessions.findActive(TokenDigester.sha256(token), clock.instant())
                    .ifPresent(session -> authenticate(session, token));
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(AccessSessionRepository.SessionAccount session, String credentials) {
        var principal = new AuthenticatedAccount(
            session.accountId(), session.email(), session.role());
        var authority = new SimpleGrantedAuthority("ROLE_" + session.role().name());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal, credentials, List.of(authority));
        authentication.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
