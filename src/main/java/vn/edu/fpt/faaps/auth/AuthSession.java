package vn.edu.fpt.faaps.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AuthSession(
        UUID id,
        String fullName,
        String email,
        String role,
        String status) implements Serializable, Principal {

    @Override
    public String getName() {
        return id.toString();
    }

    public static AuthSession from(AuthAccount account) {
        return new AuthSession(account.id(), account.fullName(), account.email(),
                account.role(), account.status());
    }
}
