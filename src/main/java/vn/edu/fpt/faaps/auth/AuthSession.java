package vn.edu.fpt.faaps.auth;

import java.io.Serializable;
import java.util.UUID;

public record AuthSession(
        UUID id,
        String fullName,
        String email,
        String avatarUrl,
        String role,
        String status) implements Serializable {

    public static AuthSession from(AuthAccount account) {
        return new AuthSession(account.id(), account.fullName(), account.email(),
                account.avatarUrl(), account.role(), account.status());
    }
}
