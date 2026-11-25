package vn.edu.fpt.faaps.user.service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.EmailCheckItem;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;

final class UserRoleEmailHelper {

    private static final Pattern BASIC_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JdbcTemplate jdbc;

    UserRoleEmailHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Validates an optional replacement email when changing a user's role.
     * Returns the normalized email to persist, or null when no email change was requested.
     */
    String validateReplacementEmail(UUID userId, String requestedEmail, String targetRole) {
        if (requestedEmail == null || requestedEmail.isBlank()) {
            return null;
        }
        String normalized = requestedEmail.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !BASIC_EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        String requiredDomain = "STUDENT".equals(targetRole) ? "@fpt.edu.vn" : "@fe.edu.vn";
        if (!normalized.endsWith(requiredDomain)) {
            throw new IllegalArgumentException(
                    "STUDENT".equals(targetRole)
                            ? "Student accounts must use an @fpt.edu.vn email address"
                            : "Advisor accounts must use an @fe.edu.vn email address");
        }
        if (isEmailTakenByOther(normalized, userId)) {
            throw new Conflict("Email " + normalized + " is already in use by another account");
        }
        return normalized;
    }

    Map<String, Boolean> checkEmailAvailability(List<EmailCheckItem> items) {
        Map<String, Boolean> result = new HashMap<>();
        if (items == null) {
            return result;
        }
        for (EmailCheckItem item : items) {
            if (item == null || item.email() == null || item.email().isBlank()) {
                continue;
            }
            String normalized = item.email().strip().toLowerCase(Locale.ROOT);
            result.put(normalized, !isEmailTakenByOther(normalized, item.userId()));
        }
        return result;
    }

    boolean isEmailTakenByOther(String normalizedEmail, UUID excludeUserId) {
        Integer count = excludeUserId == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE LOWER(email) = ?",
                        Integer.class, normalizedEmail)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE LOWER(email) = ? AND id <> ?",
                        Integer.class, normalizedEmail, excludeUserId.toString());
        return count != null && count > 0;
    }
}
