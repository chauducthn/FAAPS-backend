package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class JdbcAuthAccountStoreTest {
    private JdbcTemplate jdbcTemplate;
    private JdbcAuthAccountStore accountStore;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36) NOT NULL,
                    email VARCHAR(255) UNIQUE NOT NULL, password_hash VARCHAR(255),
                    auth_provider VARCHAR(20), external_id VARCHAR(255),
                    full_name VARCHAR(255) NOT NULL, avatar_url VARCHAR(500),
                    status VARCHAR(20), email_verified_at TIMESTAMP, updated_at TIMESTAMP,
                    UNIQUE (auth_provider, external_id)
                )
                """);
        for (String role : new String[] { "ADMIN", "ADVISOR", "STUDENT" }) {
            jdbcTemplate.update("INSERT INTO roles (id, code) VALUES (?, ?)", UUID.randomUUID().toString(), role);
        }
        accountStore = new JdbcAuthAccountStore(dataSource);
    }

    @Test
    void firstGoogleSignInCreatesOnlyStudentUserAndSubsequentSignInFindsSameUser() {
        GoogleIdentity identity = new GoogleIdentity("google-sub", "STUDENT@example.com", "Student", null);

        AuthAccount first = accountStore.resolveGoogleAccount(identity);
        AuthAccount second = accountStore.resolveGoogleAccount(identity);

        assertEquals(first.id(), second.id());
        assertEquals("STUDENT", first.role());
        assertEquals("student@example.com", first.email());
        assertNull(first.passwordHash());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals("GOOGLE", jdbcTemplate.queryForObject(
                "SELECT auth_provider FROM users WHERE id = ?", String.class, first.id().toString()));
    }

    @Test
    void verifiedGoogleEmailLinksPreauthorizedAdvisorButNotAdmin() {
        String advisorId = addUser("advisor@example.com", "ADVISOR", "ACTIVE", null, null);
        String adminId = addUser("admin@example.com", "ADMIN", "ACTIVE", null,
                new BCryptPasswordEncoder().encode("secret"));

        AuthAccount advisor = accountStore.resolveGoogleAccount(
                new GoogleIdentity("advisor-sub", "advisor@example.com", "Advisor", null));

        assertEquals(advisorId, advisor.id().toString());
        assertEquals("ADVISOR", advisor.role());
        assertEquals("advisor-sub", jdbcTemplate.queryForObject(
                "SELECT external_id FROM users WHERE id = ?", String.class, advisorId));
        assertThrows(BadCredentialsException.class, () -> accountStore.resolveGoogleAccount(
                new GoogleIdentity("admin-sub", "admin@example.com", "Admin", null)));
        assertNull(jdbcTemplate.queryForObject("SELECT external_id FROM users WHERE id = ?", String.class, adminId));
    }

    @Test
    void differentGoogleSubjectCannotTakeOverAnExistingEmail() {
        AuthAccount original = accountStore.resolveGoogleAccount(
                new GoogleIdentity("original-sub", "student@example.com", "Student", null));

        assertThrows(BadCredentialsException.class, () -> accountStore.resolveGoogleAccount(
                new GoogleIdentity("different-sub", "student@example.com", "Student", null)));
        assertEquals(original.id(), accountStore.resolveGoogleAccount(
                new GoogleIdentity("original-sub", "student@example.com", "Student", null)).id());
    }

    @Test
    void suspendedAccountCannotStartGoogleSession() {
        AuthAccount account = accountStore.resolveGoogleAccount(
                new GoogleIdentity("student-sub", "student@example.com", "Student", null));
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", account.id().toString());

        AuthService authService = new AuthService(accountStore, new BCryptPasswordEncoder());
        assertThrows(DisabledException.class, () -> authService.loginGoogle(
                new GoogleIdentity("student-sub", "student@example.com", "Student", null)));
    }

    private String addUser(String email, String role, String status, String externalId, String passwordHash) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO users (id, role_id, email, password_hash, auth_provider, external_id,
                                   full_name, status)
                SELECT ?, id, ?, ?, 'LOCAL', ?, ?, ? FROM roles WHERE code = ?
                """, id, email, passwordHash, externalId, email, status, role);
        return id;
    }
}
