package vn.edu.fpt.faaps.auth.service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthPasswordService {
    /** At least 8 characters, at most 72 (BCrypt limit), with at least one letter and one digit. */
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$");
    private static final int RESET_TOKEN_TTL_MINUTES = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthPasswordService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthPasswordService(JdbcTemplate jdbc) {
        this(jdbc, new BCryptPasswordEncoder());
    }

    @Transactional
    public ForgotPasswordResult forgotPassword(String email, String ipAddress) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        List<UserRow> users = jdbc.query("""
                SELECT id, email, full_name, status
                FROM users
                WHERE LOWER(email) = ?
                """, (rs, row) -> new UserRow(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("status")
        ), normalizedEmail);

        String genericMessage = "If an active account with that email exists, password reset instructions have been sent.";
        if (users.isEmpty() || !"ACTIVE".equalsIgnoreCase(users.get(0).status())) {
            return new ForgotPasswordResult(true, genericMessage, null);
        }

        UserRow user = users.get(0);
        jdbc.update("""
                UPDATE password_reset_tokens
                SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND used_at IS NULL
                """, user.id());

        String token = generateResetToken();
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES));
        String cleanIp = ipAddress != null && ipAddress.length() > 45 ? ipAddress.substring(0, 45) : ipAddress;

        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token, expires_at, used_at, ip_address, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), user.id(), token, expiresAt, cleanIp);

        dispatchPasswordResetEmail(user.email(), user.fullName(), token);
        return new ForgotPasswordResult(true, genericMessage, token);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }
        validatePassword(newPassword);

        List<TokenRow> tokens = jdbc.query("""
                SELECT id, user_id, expires_at, used_at
                FROM password_reset_tokens
                WHERE token = ?
                FOR UPDATE
                """, (rs, row) -> new TokenRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getTimestamp("expires_at"),
                rs.getTimestamp("used_at")
        ), token.strip());

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Reset token is invalid or has expired");
        }
        TokenRow resetToken = tokens.get(0);
        if (resetToken.usedAt() != null
                || resetToken.expiresAt() == null
                || resetToken.expiresAt().toLocalDateTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Reset token is invalid or has expired");
        }

        String hash = passwordEncoder.encode(newPassword);
        int updated = jdbc.update("""
                UPDATE users
                SET password_hash = ?,
                    auth_provider = 'LOCAL',
                    failed_login_attempts = 0,
                    locked_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE'
                """, hash, resetToken.userId());
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
        validatePassword(newPassword);
        if (currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException("New password must be different from current password");
        }

        List<String> hashes = jdbc.query("""
                SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, (rs, row) -> rs.getString("password_hash"), userId.toString());
        if (hashes.isEmpty()) {
            throw new IllegalArgumentException("Account not found or inactive");
        }
        String existingHash = hashes.get(0);
        if (existingHash == null || !passwordEncoder.matches(currentPassword, existingHash)) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        String newHash = passwordEncoder.encode(newPassword);
        jdbc.update("""
                UPDATE users
                SET password_hash = ?,
                    auth_provider = 'LOCAL',
                    failed_login_attempts = 0,
                    locked_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, newHash, userId.toString());
    }

    private static void validatePassword(String password) {
        if (password == null || !PASSWORD.matcher(password).matches()) {
            throw new IllegalArgumentException("Password must be 8-72 characters with at least one letter and one number");
        }
    }

    private static String generateResetToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Extension hook for sending the password reset link/token via email when an SMTP server is configured.
     */
    private void dispatchPasswordResetEmail(String email, String fullName, String token) {
        // [EXTENSION HOOK]: Wire JavaMailSender / notification email service here when SMTP is enabled.
    }

    private record UserRow(String id, String email, String fullName, String status) {}
    private record TokenRow(String id, String userId, Timestamp expiresAt, Timestamp usedAt) {}
    public record ForgotPasswordResult(boolean success, String message, String resetToken) {}
}
