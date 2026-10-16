package com.locksteptickets.hold;

import java.util.List;
import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    HoldService.HoldResponse create(
        @PathVariable UUID eventId,
        @Valid @RequestBody CreateHoldRequest request,
        @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        return holdService.create(eventId, request.seatIds(), account);
    }

    public record CreateHoldRequest(
        @NotEmpty @Size(max = 6) List<@NotNull UUID> seatIds
    ) {}
}
