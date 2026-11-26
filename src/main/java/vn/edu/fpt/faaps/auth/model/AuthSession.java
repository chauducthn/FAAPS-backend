package vn.edu.fpt.faaps.auth.model;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AuthSession(
        UUID id,
        String fullName,
        String email,
        String role,
        String status,
        boolean mustChangePassword,
        String avatarUrl) implements Serializable, Principal {

    public AuthSession(UUID id, String fullName, String email, String role, String status) {
        this(id, fullName, email, role, status, false, null);
    }

    public AuthSession(UUID id, String fullName, String email, String role, String status,
            boolean mustChangePassword) {
        this(id, fullName, email, role, status, mustChangePassword, null);
    }

    @Override
    public String getName() {
        return id.toString();
    }

    public static AuthSession from(AuthAccount account) {
        return from(account, false);
    }

    public static AuthSession from(AuthAccount account, boolean mustChangePassword) {
        return new AuthSession(account.id(), account.fullName(), account.email(),
                account.role(), account.status(), mustChangePassword, account.avatarUrl());
    }
}
