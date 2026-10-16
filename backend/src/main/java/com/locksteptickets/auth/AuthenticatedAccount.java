package com.locksteptickets.auth;

import java.security.Principal;
import java.util.UUID;

public record AuthenticatedAccount(UUID accountId, String email, Account.Role role)
    implements Principal {

    @Override
    public String getName() {
        return accountId.toString();
    }
}
