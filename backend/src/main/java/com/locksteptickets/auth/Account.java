package com.locksteptickets.auth;

import java.util.UUID;

public record Account(UUID id, String email, String passwordHash, Role role, boolean active) {

    public enum Role {
        CUSTOMER,
        ADMIN
    }
}
