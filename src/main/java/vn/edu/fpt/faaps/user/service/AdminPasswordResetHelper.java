package vn.edu.fpt.faaps.user.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetEntry;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetResponse;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

final class AdminPasswordResetHelper {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetHelper.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    AdminPasswordResetHelper(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, EmailService emailService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    PasswordResetResponse resetPasswords(List<UUID> userIds) {
        List<PasswordResetEntry> resetResults = new ArrayList<>();
        Set<String> generatedPasswords = new HashSet<>();
        int changed = 0;
        String firstPassword = null;

        for (UUID id : userIds.stream().sorted().toList()) {
            ResetTarget target = findTargetForUpdate(id);
            String newPassword;
            do {
                newPassword = generateRandomPassword();
            } while (!generatedPasswords.add(newPassword));

            if (firstPassword == null) {
                firstPassword = newPassword;
            }

            int rows = jdbc.update("""
                    UPDATE users
                    SET password_hash = ?, auth_provider = 'LOCAL',
                        failed_login_attempts = 0, locked_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, passwordEncoder.encode(newPassword), id.toString());
            changed += rows;

            recordAuditLog(id);
            if (emailService != null) {
                emailService.sendAdminResetPassword(target.email(), target.fullName(), newPassword);
            }
            resetResults.add(new PasswordResetEntry(
                    target.userCode(), target.email(), target.fullName(), "SUCCESS", newPassword, null));
        }

        return new PasswordResetResponse(changed, firstPassword, resetResults);
    }

    private ResetTarget findTargetForUpdate(UUID id) {
        List<ResetTarget> targets = jdbc.query("""
                SELECT u.id,
                       CASE WHEN r.code = 'STUDENT' THEN COALESCE(s.student_code, u.email) ELSE u.email END
                           AS user_code,
                       u.email, u.full_name, u.status
                FROM users u
                LEFT JOIN roles r ON r.id = u.role_id
                LEFT JOIN students s ON s.user_id = u.id
                WHERE u.id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ResetTarget(
                UUID.fromString(rs.getString("id")),
                rs.getString("user_code"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("status")), id.toString());
        if (targets.isEmpty()) {
            throw new NotFound("User " + id + " was not found");
        }
        return targets.get(0);
    }

    private void recordAuditLog(UUID userId) {
        try {
            jdbc.update("""
                    INSERT INTO audit_logs (id, user_id, action, entity_type, entity_id, created_at)
                    VALUES (?, ?, 'RESET_PASSWORD', 'USER', ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), userId.toString(), userId.toString());
        } catch (Exception ex) {
            log.debug("Skipping audit log for password reset of user {}: {}", userId, ex.getMessage());
        }
    }

    private static String generateRandomPassword() {
        return "Faaps@" + (100000 + RANDOM.nextInt(900000));
    }

    private record ResetTarget(UUID id, String userCode, String email, String fullName, String status) {}
}
