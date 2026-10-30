package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;
import vn.edu.fpt.faaps.auth.repository.AuthAccountStore;
import vn.edu.fpt.faaps.auth.service.AuthService;

class AuthServicePasswordLoginTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UUID studentId = UUID.randomUUID();

    private AuthService service(String status) {
        AuthAccount student = new AuthAccount(studentId, "s@fpt.edu.vn", "Student", "STUDENT", status,
                encoder.encode("Secret123"));
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@faaps.local", "Admin", "ADMIN", "ACTIVE",
                encoder.encode("Admin@12345"));
        return new AuthService(new AuthAccountStore() {
            @Override
            public Optional<AuthAccount> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public Optional<AuthAccount> findAdminByEmail(String email) {
                return email.equals("admin") || email.equals(admin.email()) ? Optional.of(admin) : Optional.empty();
            }

            @Override
            public Optional<AuthAccount> findLocalByEmail(String email) {
                return email.equalsIgnoreCase(student.email()) ? Optional.of(student) : Optional.empty();
            }

            @Override
            public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
                throw new UnsupportedOperationException();
            }
        }, encoder);
    }

    @Test
    void aRegisteredStudentSignsInWithEmailAndPassword() {
        var session = service("ACTIVE").login(" S@fpt.edu.vn ", "Secret123");
        assertEquals(studentId, session.id());
        assertEquals("STUDENT", session.role());
    }

    @Test
    void theAdminStillSignsInThroughTheSameEntryPoint() {
        assertEquals("ADMIN", service("ACTIVE").login("admin", "Admin@12345").role());
        assertEquals("ADMIN", service("ACTIVE").login("admin@faaps.local", "Admin@12345").role());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        var wrong = assertThrows(BadCredentialsException.class, () -> service("ACTIVE").login("s@fpt.edu.vn", "nope"));
        var unknown = assertThrows(BadCredentialsException.class, () -> service("ACTIVE").login("x@fpt.edu.vn", "Secret123"));
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test
    void anInactiveStudentCannotSignIn() {
        assertThrows(DisabledException.class, () -> service("INACTIVE").login("s@fpt.edu.vn", "Secret123"));
    }
}
