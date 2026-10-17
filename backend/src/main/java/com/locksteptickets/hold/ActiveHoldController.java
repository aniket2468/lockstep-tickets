package com.locksteptickets.hold;

import java.util.List;

import com.locksteptickets.auth.AuthenticatedAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holds")
public class ActiveHoldController {

    private final HoldService holdService;

    public ActiveHoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @GetMapping("/active")
    List<HoldService.HoldResponse> active(
        @AuthenticationPrincipal AuthenticatedAccount account
    ) {
        return holdService.listActive(account);
    }
}
