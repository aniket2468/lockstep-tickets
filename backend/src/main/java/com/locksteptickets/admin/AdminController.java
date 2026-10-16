package com.locksteptickets.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated @RestController @RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    AdminController(AdminService service){this.service=service;}
    @PostMapping("/events/{event}/seats/{seat}/block") AdminService.SeatResponse block(
        @PathVariable UUID event,@PathVariable UUID seat,@Valid @RequestBody ReasonRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.block(event,seat,request.reason(),actor);}
    @PostMapping("/events/{event}/seats/{seat}/release") AdminService.SeatResponse release(
        @PathVariable UUID event,@PathVariable UUID seat,@Valid @RequestBody ReasonRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.release(event,seat,request.reason(),actor);}
    @PostMapping("/events/{event}/reschedule") AdminService.RescheduleResponse reschedule(
        @PathVariable UUID event,@Valid @RequestBody RescheduleRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.reschedule(event,request.startsAt(),request.reason(),actor);}
    @PostMapping("/events/{event}/cancel") AdminService.CancellationResponse cancel(
        @PathVariable UUID event,@Valid @RequestBody CancelEventRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.cancel(event,request.reason(),actor);}
    @PostMapping("/bookings/{booking}/refunds") ResponseEntity<AdminService.ExceptionalResult> refund(
        @PathVariable UUID booking,@RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ExceptionalRefundRequest request,@AuthenticationPrincipal AuthenticatedAccount actor){
        var result=service.exceptional(booking,request.ticketIds(),key,request.reason(),actor);
        return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED).body(result);}
    @GetMapping("/audit") List<AdminRepository.AuditRow> audit(
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue="0") @Min(0) @Max(5000) int offset,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.audits(limit,offset,actor);}
    public record ReasonRequest(@NotBlank @Size(max=500) String reason){}
    public record RescheduleRequest(@NotNull Instant startsAt,@NotBlank @Size(max=500) String reason){}
    public record CancelEventRequest(@NotBlank @Size(max=500) String reason){}
    public record ExceptionalRefundRequest(@NotEmpty @Size(max=6) List<@NotNull UUID> ticketIds,
        @NotBlank @Size(max=500) String reason){}
}
