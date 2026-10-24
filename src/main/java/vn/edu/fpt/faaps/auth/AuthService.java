package vn.edu.fpt.faaps.auth;

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

    public AuthService(AuthAccountStore accountStore, PasswordEncoder passwordEncoder) {
        this.accountStore = accountStore;
        this.passwordEncoder = passwordEncoder;
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
