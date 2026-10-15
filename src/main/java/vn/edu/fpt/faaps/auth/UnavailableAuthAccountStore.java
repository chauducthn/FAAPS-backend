package vn.edu.fpt.faaps.auth;

import java.util.Optional;
import java.util.UUID;

public class UnavailableAuthAccountStore implements AuthAccountStore {
    @Override
    public Optional<AuthAccount> findById(UUID id) {
        throw new AuthUnavailableException();
    }

    @Override
    public Optional<AuthAccount> findAdminByEmail(String email) {
        throw new AuthUnavailableException();
    }

    @Override
    public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
        throw new AuthUnavailableException();
    }
}
