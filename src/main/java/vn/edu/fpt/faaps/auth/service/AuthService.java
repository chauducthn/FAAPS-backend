package vn.edu.fpt.faaps.auth.service;

import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;
import vn.edu.fpt.faaps.auth.repository.AuthAccountStore;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
public class AuthService {
    private final AuthAccountStore accountStore;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public AuthService(AuthAccountStore accountStore, PasswordEncoder passwordEncoder) {
        this.accountStore = accountStore;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthSession loginAdmin(String email, String password) {
        String loginEmail = email.strip().equalsIgnoreCase("admin") ? "admin" : email.strip();
        AuthAccount account = accountStore.findAdminByEmail(loginEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!"ADMIN".equals(account.role()) || account.passwordHash() == null
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return activeSession(account);
    }

    /** Email + password sign-in for every kind of account that has a password: Admin, or a registered Student. */
    public AuthSession login(String email, String password) {
        String loginEmail = email.strip();
        if (loginEmail.equalsIgnoreCase("admin") || accountStore.findAdminByEmail(loginEmail).isPresent()) {
            return loginAdmin(loginEmail, password);
        }
        AuthAccount account = accountStore.findLocalByEmail(loginEmail).orElse(null);
        // Compare against a dummy hash when the account is unknown so the response time does not reveal it.
        String hash = account == null ? dummyHash : account.passwordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (account == null || !matches) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return activeSession(account);
    }

    public AuthSession loginGoogle(GoogleIdentity identity) {
        return activeSession(accountStore.resolveGoogleAccount(identity));
    }

    public AuthSession sessionFor(UUID userId) {
        AuthAccount account = accountStore.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Account is no longer available"));
        return activeSession(account);
    }

    private AuthSession activeSession(AuthAccount account) {
        if (!"ACTIVE".equals(account.status())) {
            throw new DisabledException("Account is not active");
        }
        return AuthSession.from(account);
    }

    public Authentication authentication(AuthSession session) {
        return new UsernamePasswordAuthenticationToken(session, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + session.role())));
    }
}
