package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {
    private AuthAccountStore accountStore;
    private AuthService authService;
    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        accountStore = mock(AuthAccountStore.class);
        passwordEncoder = new BCryptPasswordEncoder();
        authService = new AuthService(accountStore, passwordEncoder);
    }

    @Test
    void adminLoginAcceptsActiveAdminAndGrantsAdminRole() {
        AuthAccount account = account("ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        when(accountStore.findAdminByEmail(account.email())).thenReturn(Optional.of(account));

        AuthSession session = authService.loginAdmin(account.email(), "secret");

        assertEquals(account.id(), session.id());
        assertEquals("ROLE_ADMIN", authService.authentication(session).getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void adminLoginAcceptsAdminAlias() {
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@faaps.local", "Admin",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        when(accountStore.findAdminByEmail(admin.email())).thenReturn(Optional.of(admin));

        assertEquals("ADMIN", authService.loginAdmin("admin", "secret").role());
        verify(accountStore).findAdminByEmail("admin@faaps.local");
    }

    @Test
    void adminLoginRejectsWrongPasswordAndNonAdminAccount() {
        AuthAccount admin = account("ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        when(accountStore.findAdminByEmail(admin.email())).thenReturn(Optional.of(admin));
        assertThrows(BadCredentialsException.class, () -> authService.loginAdmin(admin.email(), "wrong"));

        AuthAccount student = account("STUDENT", "ACTIVE", passwordEncoder.encode("secret"));
        when(accountStore.findAdminByEmail(student.email())).thenReturn(Optional.of(student));
        assertThrows(BadCredentialsException.class, () -> authService.loginAdmin(student.email(), "secret"));
    }

    @Test
    void googleLoginUsesStoredRoleAndRejectsSuspendedAccount() {
        GoogleIdentity identity = new GoogleIdentity("google-sub", "person@example.com", "Person");
        AuthAccount advisor = account("ADVISOR", "ACTIVE", null);
        when(accountStore.resolveGoogleAccount(identity)).thenReturn(advisor);

        assertEquals("ADVISOR", authService.loginGoogle(identity).role());
        verify(accountStore).resolveGoogleAccount(identity);

        when(accountStore.resolveGoogleAccount(identity)).thenReturn(account("STUDENT", "SUSPENDED", null));
        assertThrows(DisabledException.class, () -> authService.loginGoogle(identity));
    }

    private AuthAccount account(String role, String status, String passwordHash) {
        return new AuthAccount(UUID.randomUUID(), "person@example.com", "Person",
                role, status, passwordHash);
    }
}
