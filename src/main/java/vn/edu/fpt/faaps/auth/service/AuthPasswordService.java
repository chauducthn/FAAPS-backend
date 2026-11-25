package vn.edu.fpt.faaps.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;

@Service
public class AuthPasswordService {

    private static final Logger log = LoggerFactory.getLogger(AuthPasswordService.class);
    private static final int RESET_TOKEN_TTL_MINUTES = 30;
    private static final String GENERIC_RESET_MESSAGE =
            "If an active account with that email exists, password reset instructions have been sent.";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetSupport support;

    @Autowired
    public AuthPasswordService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl,
            @Autowired(required = false) EmailService emailService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.support = new PasswordResetSupport(frontendUrl, emailService);
    }

    public AuthPasswordService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, String frontendUrl) {
        this(jdbc, passwordEncoder, frontendUrl, null);
    }

    public AuthPasswordService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this(jdbc, passwordEncoder, "http://localhost:5173", null);
    }

    public AuthPasswordService(JdbcTemplate jdbc) {
        this(jdbc, new BCryptPasswordEncoder(), "http://localhost:5173", null);
    }

    @Transactional
    public ForgotPasswordResult forgotPassword(String email, String ipAddress) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        support.checkRateLimit(normalizedEmail, ipAddress);

        List<UserRow> users = findEligibleUserByEmail(normalizedEmail);
        if (users.isEmpty() || !"ACTIVE".equalsIgnoreCase(users.get(0).status())
                || "ADMIN".equalsIgnoreCase(users.get(0).role())) {
            return new ForgotPasswordResult(true, GENERIC_RESET_MESSAGE, null);
        }

        UserRow user = users.get(0);
        jdbc.update("""
                UPDATE password_reset_tokens
                SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND used_at IS NULL
                """, user.id());

        String rawToken = PasswordResetSupport.generateResetToken();
        String tokenHash = sha256Hex(rawToken);
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES));
        String cleanIp = ipAddress != null && ipAddress.length() > 45 ? ipAddress.substring(0, 45) : ipAddress;

        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token, expires_at, used_at, ip_address, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), user.id(), tokenHash, expiresAt, cleanIp);

        support.dispatchPasswordResetEmail(user.email(), user.fullName(), rawToken, RESET_TOKEN_TTL_MINUTES);
        return new ForgotPasswordResult(true, GENERIC_RESET_MESSAGE, rawToken);
    }

    /** Clears in-memory rate limit counters (for tests). */
    public void clearRateLimits() {
        support.clearRateLimits();
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }
        PasswordResetSupport.validatePassword(newPassword);

        String tokenHash = sha256Hex(token.strip());
        List<TokenRow> tokens = jdbc.query("""
                SELECT id, user_id, expires_at, used_at
                FROM password_reset_tokens
                WHERE token = ?
                FOR UPDATE
                """, (rs, row) -> new TokenRow(
                rs.getString("id"), rs.getString("user_id"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("used_at")), tokenHash);

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Reset token is invalid or has expired");
        }
        TokenRow resetToken = tokens.get(0);
        if (resetToken.usedAt() != null || resetToken.expiresAt() == null
                || resetToken.expiresAt().toLocalDateTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Reset token is invalid or has expired");
        }

        int updated = jdbc.update("""
                UPDATE users
                SET password_hash = ?, auth_provider = 'LOCAL', failed_login_attempts = 0,
                    locked_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, passwordEncoder.encode(newPassword), resetToken.userId());
        if (updated == 0) {
            throw new IllegalArgumentException("Account is not active or no longer exists");
        }
        jdbc.update("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE id = ?", resetToken.id());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        if (userId == null) {
            throw new BadCredentialsException("Authentication required");
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required");
        }
        PasswordResetSupport.validatePassword(newPassword);
        if (currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("New password must be different from current password");
        }

        List<String> hashes = jdbc.query(
                "SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> rs.getString("password_hash"), userId.toString());
        if (hashes.isEmpty()) {
            throw new IllegalArgumentException("Account not found or inactive");
        }
        String existingHash = hashes.get(0);
        if (existingHash == null || !passwordEncoder.matches(currentPassword, existingHash)) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        jdbc.update("""
                UPDATE users
                SET password_hash = ?, auth_provider = 'LOCAL', failed_login_attempts = 0,
                    locked_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, passwordEncoder.encode(newPassword), userId.toString());
    }

    static String sha256Hex(String value) {
        return PasswordResetSupport.sha256Hex(value);
    }

    private List<UserRow> findEligibleUserByEmail(String normalizedEmail) {
        try {
            return jdbc.query("""
                    SELECT u.id, u.email, u.full_name, u.status, r.code AS role_code
                    FROM users u LEFT JOIN roles r ON r.id = u.role_id
                    WHERE LOWER(u.email) = ?
                    """, (rs, row) -> new UserRow(
                    rs.getString("id"), rs.getString("email"), rs.getString("full_name"),
                    rs.getString("status"), rs.getString("role_code")), normalizedEmail);
        } catch (Exception ex) {
            log.debug("Falling back to users-only lookup for password reset: {}", ex.getMessage());
            return jdbc.query("""
                    SELECT id, email, full_name, status FROM users WHERE LOWER(email) = ?
                    """, (rs, row) -> new UserRow(
                    rs.getString("id"), rs.getString("email"), rs.getString("full_name"),
                    rs.getString("status"), null), normalizedEmail);
        }
    }

    private record UserRow(String id, String email, String fullName, String status, String role) {}

    private record TokenRow(String id, String userId, Timestamp expiresAt, Timestamp usedAt) {}

    /**
     * `resetToken` is annotated with @JsonIgnore so it is NEVER serialized in HTTP responses,
     * while remaining accessible to internal Java unit tests.
     */
    public record ForgotPasswordResult(boolean success, String message, @JsonIgnore String resetToken) {}
}
