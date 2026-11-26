package vn.edu.fpt.faaps.user.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.common.validation.IdentityInput;

@Service
public class UserProfileService {
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final JdbcTemplate jdbc;
    private final boolean hasAvatarColumn;

    public UserProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.hasAvatarColumn = detectAvatarColumn(jdbc);
    }

    private static boolean detectAvatarColumn(JdbcTemplate jdbc) {
        try {
            jdbc.query("SELECT avatar_url FROM users WHERE 1 = 0", (rs, row) -> null);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public UserProfile getProfile(UUID userId) {
        String avatarExpr = hasAvatarColumn ? "u.avatar_url" : "NULL AS avatar_url";
        return jdbc.query(
                "SELECT u.id, u.email, u.full_name, u.phone, u.date_of_birth,"
                + " r.code AS role_code, u.status, u.auth_provider,"
                + " u.last_login_at, u.created_at, " + avatarExpr
                + " FROM users u JOIN roles r ON r.id = u.role_id WHERE u.id = ?",
                (rs, row) -> new UserProfile(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        IdentityInput.compactPhone(rs.getString("phone")),
                        rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("role_code"),
                        rs.getString("status"),
                        rs.getString("auth_provider"),
                        rs.getObject("last_login_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getString("avatar_url")),
                userId.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found"));
    }

    @Transactional
    public UserProfile updateProfile(UUID userId, String fullName, String phone) {
        return updateProfile(userId, fullName, phone, null, null);
    }

    @Transactional
    public UserProfile updateProfile(UUID userId, String fullName, String phone, String email, LocalDate dateOfBirth) {
        String normalizedName = IdentityInput.fullName(fullName);
        String normalizedPhone = IdentityInput.compactPhone(phone);
        String normalizedEmail = email == null || email.isBlank() ? null : email.strip().toLowerCase();
        if (normalizedEmail != null) {
            if (!normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new IllegalArgumentException("Please enter a valid email address");
            }
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE LOWER(email) = ? AND id <> ?",
                    Integer.class, normalizedEmail, userId.toString());
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("Email address is already in use");
            }
        }
        if (dateOfBirth != null && (dateOfBirth.isAfter(LocalDate.now()) || dateOfBirth.getYear() < 1900)) {
            throw new IllegalArgumentException("Date of birth must be a valid past date");
        }
        int updated = jdbc.update(
                "UPDATE users SET full_name = ?, phone = ?, email = COALESCE(?, email),"
                        + " date_of_birth = COALESCE(?, date_of_birth), updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?",
                normalizedName,
                normalizedPhone,
                normalizedEmail,
                dateOfBirth,
                userId.toString());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found");
        }
        return getProfile(userId);
    }

    @Transactional
    public UserProfile updateAvatar(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Avatar image file is required");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Avatar image must be 5 MB or smaller");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Avatar must be a PNG, JPEG, WebP, or GIF image");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read uploaded image");
        }
        String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        int updated = jdbc.update(
                "UPDATE users SET avatar_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                dataUrl,
                userId.toString());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found");
        }
        return getProfile(userId);
    }

    @Transactional
    public UserProfile removeAvatar(UUID userId) {
        int updated = jdbc.update(
                "UPDATE users SET avatar_url = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                userId.toString());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found");
        }
        return getProfile(userId);
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
            LocalDateTime createdAt,
            String avatarUrl) {}
}
