package com.locksteptickets.refund;

import java.util.List;
import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api")
public class RefundController {
    private final RefundService service;
    RefundController(RefundService service){this.service=service;}

    @GetMapping("/bookings") List<RefundRepository.BookingSummary> bookings(
        @AuthenticationPrincipal AuthenticatedAccount account){return service.bookings(account);}
    @GetMapping("/bookings/{id}") RefundService.BookingDetail booking(@PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedAccount account){return service.booking(id,account);}

    @PostMapping("/bookings/{id}/cancellations")
    ResponseEntity<RefundService.RefundResponse> cancel(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CancelRequest request,
        @AuthenticationPrincipal AuthenticatedAccount account){
        var result=service.cancel(id,request.ticketIds(),key,account);
        return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED).body(result.refund());
    }
    @PostMapping("/refunds/{id}/reconcile") RefundService.RefundResponse reconcile(@PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedAccount account){return service.reconcile(id,account);}
    @PostMapping("/refunds/{id}/retry") RefundService.RefundResponse retry(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody RetryRequest request,
        @AuthenticationPrincipal AuthenticatedAccount account){return service.retry(id,key,account);}

    public record CancelRequest(@NotEmpty @Size(max=6) List<@NotNull UUID> ticketIds){}
    public record RetryRequest(){}
}
