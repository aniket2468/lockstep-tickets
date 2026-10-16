package com.locksteptickets.waitlist;

import java.util.List;
import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WaitlistController {
    private final WaitlistService service;
    WaitlistController(WaitlistService service){this.service=service;}

    @PostMapping("/api/events/{eventId}/waitlist") @ResponseStatus(HttpStatus.CREATED)
    WaitlistService.EntryResponse join(@PathVariable UUID eventId,@Valid @RequestBody JoinRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.join(eventId,request.preferredSectionId(),request.quantity(),actor);}
    @GetMapping("/api/waitlist") List<WaitlistService.EntryResponse> list(
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.list(actor);}
    @PostMapping("/api/waitlist/{entryId}/claim") @ResponseStatus(HttpStatus.CREATED)
    WaitlistService.ClaimResponse claim(@PathVariable UUID entryId,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.claim(entryId,actor);}
    @DeleteMapping("/api/waitlist/{entryId}")
    WaitlistService.EntryResponse cancel(@PathVariable UUID entryId,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.cancel(entryId,actor);}
    @PostMapping("/api/admin/events/{eventId}/waitlist/promotions")
    WaitlistService.PromotionResponse promote(@PathVariable UUID eventId,@Valid @RequestBody ReasonRequest request,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.promote(eventId,request.reason(),actor);}

    public record JoinRequest(UUID preferredSectionId,@Min(1) @Max(6) int quantity){}
    public record ReasonRequest(@NotBlank @Size(max=500) String reason){}
}
