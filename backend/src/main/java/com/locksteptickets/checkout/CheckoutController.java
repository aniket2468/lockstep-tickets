package com.locksteptickets.checkout;

import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final CheckoutService service;

    CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping("/holds/{holdId}/checkout")
    ResponseEntity<CheckoutService.BookingResponse> checkout(
        @PathVariable UUID holdId,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 100) String key,
        @Valid @RequestBody CheckoutRequest request,
        @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        var result = service.checkout(holdId, key, account);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(result.booking());
    }

    @PostMapping("/bookings/{bookingId}/reconcile")
    CheckoutService.BookingResponse reconcile(
        @PathVariable UUID bookingId,
        @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        return service.reconcile(bookingId, account);
    }

    public record CheckoutRequest() {}
}
