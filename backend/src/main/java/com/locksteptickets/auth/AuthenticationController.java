package com.locksteptickets.auth;

import java.time.Instant;
import java.util.UUID;

import com.locksteptickets.api.ApiError;
import com.locksteptickets.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final ClientIpResolver clientIpResolver;
    private final SessionCookieService cookies;

    public AuthenticationController(
        AuthenticationService authenticationService,
        ClientIpResolver clientIpResolver,
        SessionCookieService cookies
    ) {
        this.authenticationService = authenticationService;
        this.clientIpResolver = clientIpResolver;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpRequest
    ) {
        AuthenticationService.LoginResult result = authenticationService.login(
            request.email(), request.password(), clientIpResolver.resolve(httpRequest));
        String csrfToken = cookies.newCsrfToken();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE,
                cookies.sessionCookie(result.token(), result.maxAge()).toString())
            .header(HttpHeaders.SET_COOKIE,
                cookies.csrfCookie(csrfToken, result.maxAge()).toString())
            .cacheControl(CacheControl.noStore())
            .body(new LoginResponse(
                result.expiresAt(),
                AccountResponse.from(result.account())));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        authenticationService.logout(cookies.authenticationToken(request));
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clearSessionCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookies.clearCsrfCookie().toString())
            .build();
    }

    @ExceptionHandler(AuthenticationService.LoginThrottledException.class)
    ResponseEntity<ApiError> loginThrottled(
        AuthenticationService.LoginThrottledException exception,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
            .cacheControl(CacheControl.noStore())
            .body(ApiError.of("LOGIN_RATE_LIMITED", "Too many login attempts. Try again later.",
                RequestIdFilter.from(request)));
    }

    @GetMapping("/me")
    AccountResponse me(@AuthenticationPrincipal AuthenticatedAccount account) {
        return AccountResponse.from(account);
    }

    @ExceptionHandler(AuthenticationService.InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiError.of("INVALID_CREDENTIALS", "The credentials are invalid.",
                RequestIdFilter.from(request)));
    }

    public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 200) String password
    ) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=[REDACTED]]";
        }
    }

    public record LoginResponse(
        Instant expiresAt,
        AccountResponse account
    ) {
        @Override
        public String toString() {
            return "LoginResponse[expiresAt=" + expiresAt + ", account=" + account + "]";
        }
    }

    public record AccountResponse(UUID id, String email, Account.Role role) {
        static AccountResponse from(AuthenticatedAccount account) {
            return new AccountResponse(account.accountId(), account.email(), account.role());
        }
    }
}
