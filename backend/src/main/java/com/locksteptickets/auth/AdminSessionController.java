package com.locksteptickets.auth;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/session")
public class AdminSessionController {

    @GetMapping
    AdminSessionResponse current(@AuthenticationPrincipal AuthenticatedAccount account) {
        return new AdminSessionResponse(account.accountId(), account.email(), account.role());
    }

    record AdminSessionResponse(UUID id, String email, Account.Role role) {
    }
}
