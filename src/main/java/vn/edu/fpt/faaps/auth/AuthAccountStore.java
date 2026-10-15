package vn.edu.fpt.faaps.auth;

import java.util.Optional;
import java.util.UUID;

public interface AuthAccountStore {
    Optional<AuthAccount> findById(UUID id);

    Optional<AuthAccount> findAdminByEmail(String email);

    AuthAccount resolveGoogleAccount(GoogleIdentity identity);
}
