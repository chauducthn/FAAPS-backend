package vn.edu.fpt.faaps.auth.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

final class PasswordResetTokenStore {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenStore.class);

    private final JdbcTemplate jdbc;

    PasswordResetTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<UserRow> findEligibleUserByEmail(String normalizedEmail) {
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

    void replaceActiveTokens(String userId, String tokenHash, Timestamp expiresAt, String cleanIp) {
        jdbc.update("""
                UPDATE password_reset_tokens
                SET used_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND used_at IS NULL
                """, userId);
        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token, expires_at, used_at, ip_address, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), userId, tokenHash, expiresAt, cleanIp);
    }

    List<TokenRow> findByHash(String tokenHash, boolean forUpdate) {
        String sql = forUpdate
                ? "SELECT id, user_id, expires_at, used_at FROM password_reset_tokens WHERE token = ? FOR UPDATE"
                : "SELECT id, user_id, expires_at, used_at FROM password_reset_tokens WHERE token = ?";
        return jdbc.query(sql, (rs, row) -> new TokenRow(
                rs.getString("id"), rs.getString("user_id"),
                rs.getTimestamp("expires_at"), rs.getTimestamp("used_at")), tokenHash);
    }

    boolean isTokenUsable(TokenRow row) {
        return row.usedAt() == null
                && row.expiresAt() != null
                && row.expiresAt().toLocalDateTime().isAfter(LocalDateTime.now());
    }

    void markUsed(String tokenId) {
        jdbc.update("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE id = ?", tokenId);
    }

    record UserRow(String id, String email, String fullName, String status, String role) {}

    record TokenRow(String id, String userId, Timestamp expiresAt, Timestamp usedAt) {}
}
