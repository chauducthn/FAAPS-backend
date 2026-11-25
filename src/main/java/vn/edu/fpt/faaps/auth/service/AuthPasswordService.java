package vn.edu.fpt.faaps.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;

@Service
public class AuthPasswordService {
    private static final Logger log = LoggerFactory.getLogger(AuthPasswordService.class);

    /**
     * At least 8 characters, at most 72 (BCrypt limit), with at least one letter
     * and one digit.
     */
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$");
    private static final int RESET_TOKEN_TTL_MINUTES = 30;
    private static final long RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000L;
    private static final int MAX_REQUESTS_PER_EMAIL = 3;
    private static final int MAX_REQUESTS_PER_IP = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String frontendUrl;
    private final EmailService emailService;
    private final Map<String, List<Long>> emailRateLimiter = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> ipRateLimiter = new ConcurrentHashMap<>();

    @Autowired
    public AuthPasswordService(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${faaps.frontend-url:http://localhost:5173}") String frontendUrl,
            @Autowired(required = false) EmailService emailService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.frontendUrl = frontendUrl;
        this.emailService = emailService;
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
        checkRateLimit(normalizedEmail, ipAddress);

        List<UserRow> users = findEligibleUserByEmail(normalizedEmail);

        String genericMessage = "If an active account with that email exists, password reset instructions have been sent.";
        if (users.isEmpty() || !"ACTIVE".equalsIgnoreCase(users.get(0).status())
                || "ADMIN".equalsIgnoreCase(users.get(0).role())) {
            return new ForgotPasswordResult(true, genericMessage, null);
        }

        UserRow user = users.get(0);
        jdbc.update("""
                UPDATE password_reset_tokens
                SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND used_at IS NULL
                """, user.id());

        String rawToken = generateResetToken();
        String tokenHash = sha256Hex(rawToken);
        Timestamp expiresAt = Timestamp.valueOf(LocalDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES));
        String cleanIp = ipAddress != null && ipAddress.length() > 45 ? ipAddress.substring(0, 45) : ipAddress;

        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token, expires_at, used_at, ip_address, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), user.id(), tokenHash, expiresAt, cleanIp);

        dispatchPasswordResetEmail(user.email(), user.fullName(), rawToken);
        return new ForgotPasswordResult(true, genericMessage, rawToken);
    }

    private List<UserRow> findEligibleUserByEmail(String normalizedEmail) {
        try {
            return jdbc.query("""
                    SELECT u.id, u.email, u.full_name, u.status, r.code AS role_code
                    FROM users u
                    LEFT JOIN roles r ON r.id = u.role_id
                    WHERE LOWER(u.email) = ?
                    """, (rs, row) -> new UserRow(
                    rs.getString("id"),
                    rs.getString("email"),
                    rs.getString("full_name"),
                    rs.getString("status"),
                    rs.getString("role_code")), normalizedEmail);
        } catch (Exception ignored) {
            return jdbc.query("""
                    SELECT id, email, full_name, status
                    FROM users
                    WHERE LOWER(email) = ?
                    """, (rs, row) -> new UserRow(
                    rs.getString("id"),
                    rs.getString("email"),
                    rs.getString("full_name"),
                    rs.getString("status"),
                    null), normalizedEmail);
        }
    }

    private void checkRateLimit(String normalizedEmail, String ipAddress) {
        long now = System.currentTimeMillis();
        if (!allowRequest(emailRateLimiter, normalizedEmail, now, MAX_REQUESTS_PER_EMAIL)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password reset requests for this email. Please try again in 15 minutes.");
        }
        if (ipAddress != null && !ipAddress.isBlank()) {
            if (!allowRequest(ipRateLimiter, ipAddress.strip(), now, MAX_REQUESTS_PER_IP)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many password reset requests from this IP. Please try again in 15 minutes.");
            }
        }
    }

    private static boolean allowRequest(Map<String, List<Long>> store, String key, long now, int maxRequests) {
        List<Long> timestamps = store.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.removeIf(ts -> (now - ts) > RATE_LIMIT_WINDOW_MS);
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.add(now);
            return true;
        }
    }

    /** Clears in-memory rate limit counters (for tests). */
    public void clearRateLimits() {
        emailRateLimiter.clear();
        ipRateLimiter.clear();
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }
        validatePassword(newPassword);

        String cleanToken = token.strip();
        String tokenHash = sha256Hex(cleanToken);

        List<TokenRow> tokens = jdbc.query("""
                SELECT id, user_id, expires_at, used_at
                FROM password_reset_tokens
                WHERE token = ?
                FOR UPDATE
                """, (rs, row) -> new TokenRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getTimestamp("expires_at"),
                rs.getTimestamp("used_at")), tokenHash);

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
            throw new IllegalArgumentException(
                    "Password must be 8-72 characters with at least one letter and one number");
        }
    }

    private static String generateResetToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Dispatches the password reset link to the user's email address.
     * The raw token is never returned in any HTTP response.
     */
    private void dispatchPasswordResetEmail(String email, String fullName, String rawToken) {
        String resetUrl = (frontendUrl == null ? "http://localhost:5173" : frontendUrl.replaceAll("/+$", ""))
                + "/reset-password?token=" + rawToken;
        log.info(
                "Password reset requested for {} ({}). Reset link dispatched to mail handler (link prepared for delivery).",
                email, fullName);
        if (emailService != null) {
            emailService.sendPasswordResetLink(email, fullName, resetUrl, RESET_TOKEN_TTL_MINUTES);
        }
    }

    private record UserRow(String id, String email, String fullName, String status, String role) {
    }

    private record TokenRow(String id, String userId, Timestamp expiresAt, Timestamp usedAt) {
    }

    /**
     * `resetToken` is annotated with @JsonIgnore so it is NEVER serialized in HTTP
     * responses,
     * while remaining accessible to internal Java unit tests.
     */
    public record ForgotPasswordResult(boolean success, String message, @JsonIgnore String resetToken) {
    }
}
