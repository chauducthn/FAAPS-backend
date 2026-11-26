package vn.edu.fpt.faaps.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

    private static final int RESET_TOKEN_TTL_MINUTES = 30;
    private static final String GENERIC_RESET_MESSAGE =
            "If an active account with that email exists, password reset instructions have been sent.";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetSupport support;
    private final PasswordResetTokenStore tokenStore;

    @Autowired
    public AuthPasswordService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl,
            @Autowired(required = false) EmailService emailService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.support = new PasswordResetSupport(frontendUrl, emailService);
        this.tokenStore = new PasswordResetTokenStore(jdbc);
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

        List<PasswordResetTokenStore.UserRow> users = tokenStore.findEligibleUserByEmail(normalizedEmail);
        if (users.isEmpty() || !"ACTIVE".equalsIgnoreCase(users.get(0).status())
                || "ADMIN".equalsIgnoreCase(users.get(0).role())) {
            return new ForgotPasswordResult(true, GENERIC_RESET_MESSAGE, null);
        }

        PasswordResetTokenStore.UserRow user = users.get(0);
        String rawToken = PasswordResetSupport.generateResetToken();
        String tokenHash = sha256Hex(rawToken);
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES));
        String cleanIp = ipAddress != null && ipAddress.length() > 45 ? ipAddress.substring(0, 45) : ipAddress;

        tokenStore.replaceActiveTokens(user.id(), tokenHash, expiresAt, cleanIp);
        support.dispatchPasswordResetEmail(user.email(), user.fullName(), rawToken, RESET_TOKEN_TTL_MINUTES);
        return new ForgotPasswordResult(true, GENERIC_RESET_MESSAGE, rawToken);
    }

    /** Clears in-memory rate limit counters (for tests). */
    public void clearRateLimits() {
        support.clearRateLimits();
    }

    public boolean isResetTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        List<PasswordResetTokenStore.TokenRow> tokens = tokenStore.findByHash(sha256Hex(token.strip()), false);
        return !tokens.isEmpty() && tokenStore.isTokenUsable(tokens.get(0));
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }
        PasswordResetSupport.validatePassword(newPassword);

        List<PasswordResetTokenStore.TokenRow> tokens = tokenStore.findByHash(sha256Hex(token.strip()), true);
        if (tokens.isEmpty() || !tokenStore.isTokenUsable(tokens.get(0))) {
            throw new IllegalArgumentException("Reset token is invalid or has expired");
        }
        PasswordResetTokenStore.TokenRow resetToken = tokens.get(0);

        int updated = jdbc.update("""
                UPDATE users
                SET password_hash = ?, auth_provider = 'LOCAL', failed_login_attempts = 0,
                    locked_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, passwordEncoder.encode(newPassword), resetToken.userId());
        if (updated == 0) {
            throw new IllegalArgumentException("Account is not active or no longer exists");
        }
        tokenStore.markUsed(resetToken.id());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        changePassword(userId, currentPassword, newPassword, false);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, boolean mustChangePassword) {
        if (userId == null) {
            throw new BadCredentialsException("Authentication required");
        }
        boolean hasCurrent = currentPassword != null && !currentPassword.isBlank();
        if (!mustChangePassword && !hasCurrent) {
            throw new IllegalArgumentException("Current password is required");
        }
        PasswordResetSupport.validatePassword(newPassword);
        if (hasCurrent && currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("New password must be different from current password");
        }

        List<String> hashes = jdbc.query(
                "SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> rs.getString("password_hash"), userId.toString());
        if (hashes.isEmpty()) {
            throw new IllegalArgumentException("Account not found or inactive");
        }
        String existingHash = hashes.get(0);
        if (!mustChangePassword && (existingHash == null || !passwordEncoder.matches(currentPassword, existingHash))) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (existingHash != null && passwordEncoder.matches(newPassword, existingHash)) {
            throw new IllegalArgumentException("New password must be different from current password");
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

    /**
     * `resetToken` is annotated with @JsonIgnore so it is NEVER serialized in HTTP responses,
     * while remaining accessible to internal Java unit tests.
     */
    public record ForgotPasswordResult(boolean success, String message, @JsonIgnore String resetToken) {}
}
