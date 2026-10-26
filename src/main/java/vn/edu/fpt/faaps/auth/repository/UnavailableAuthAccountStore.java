package vn.edu.fpt.faaps.auth.repository;

import vn.edu.fpt.faaps.auth.exception.AuthUnavailableException;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;

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
