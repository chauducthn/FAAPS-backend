package vn.edu.fpt.faaps.user.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserProfileService {
    private static final int MAX_NAME_LENGTH = 255;

    private final JdbcTemplate jdbc;

    public UserProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserProfile getProfile(UUID userId) {
        return jdbc.query("""
                SELECT u.id, u.email, u.full_name, u.phone, u.date_of_birth,
                       r.code AS role_code, u.status, u.auth_provider,
                       u.last_login_at, u.created_at
                FROM users u
                JOIN roles r ON r.id = u.role_id
                WHERE u.id = ?
                """,
                (rs, row) -> new UserProfile(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        compactPhone(rs.getString("phone")),
                        rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("role_code"),
                        rs.getString("status"),
                        rs.getString("auth_provider"),
                        rs.getObject("last_login_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)),
                userId.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found"));
    }

    @Transactional
    public UserProfile updateProfile(UUID userId, String fullName, String phone) {
        String normalizedName = normalizeFullName(fullName);
        String normalizedPhone = normalizeOptionalPhone(phone);
        int updated = jdbc.update(
                "UPDATE users SET full_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                normalizedName,
                normalizedPhone,
                userId.toString());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found");
        }
        return getProfile(userId);
    }

    public static String normalizeFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        String trimmed = fullName.strip();
        if (trimmed.length() < 2 || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Full name must be between 2 and 255 characters");
        }
        return trimmed;
    }

    public static String normalizeOptionalPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String compact = phone.strip().replaceAll("[\\s-]+", "");
        if (!compact.matches("^\\+?\\d{8,15}$")) {
            throw new IllegalArgumentException("Phone number must contain 8 to 15 digits");
        }
        return compact;
    }

    private static String compactPhone(String phone) {
        return phone == null ? null : phone.replaceAll("\\s+", "");
    }

    public record UserProfile(
            String id,
            String email,
            String fullName,
            String phone,
            LocalDate dateOfBirth,
            String role,
            String status,
            String authProvider,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt) {}
}
