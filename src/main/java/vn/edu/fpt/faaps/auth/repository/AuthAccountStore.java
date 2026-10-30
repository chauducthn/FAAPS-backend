package vn.edu.fpt.faaps.auth.repository;

import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;

import java.util.Optional;
import java.util.UUID;

public interface AuthAccountStore {
    Optional<AuthAccount> findById(UUID id);

    Optional<AuthAccount> findAdminByEmail(String email);

    /** An account that signs in with an email and a password stored in this system (not Admin, not Google). */
    default Optional<AuthAccount> findLocalByEmail(String email) {
        return Optional.empty();
    }

    AuthAccount resolveGoogleAccount(GoogleIdentity identity);
}
