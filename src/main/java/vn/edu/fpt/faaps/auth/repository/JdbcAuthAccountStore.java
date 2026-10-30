package vn.edu.fpt.faaps.auth.repository;

import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

public class JdbcAuthAccountStore implements AuthAccountStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthAccountStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<AuthAccount> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.id = ? AND (r.code <> 'ADMIN' OR EXISTS (
                    SELECT 1 FROM admin_account a WHERE a.user_id = u.id))
                """, this::mapAccount, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<AuthAccount> findAdminByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE (u.email = ? OR ? = 'admin') AND r.code = 'ADMIN'
                  AND EXISTS (SELECT 1 FROM admin_account a WHERE a.user_id = u.id)
                """, this::mapAccount, email, email).stream().findFirst();
    }

    @Override
    public Optional<AuthAccount> findLocalByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE LOWER(u.email) = LOWER(?) AND r.code IN ('STUDENT', 'ADVISOR')
                  AND u.auth_provider = 'LOCAL' AND u.password_hash IS NOT NULL
                """, this::mapAccount, email).stream().findFirst();
    }

    @Override
    public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
        if (identity.subject() == null || identity.subject().isBlank()
                || identity.email() == null || identity.email().isBlank()) {
            throw new BadCredentialsException("Invalid Google identity");
        }

        String subject = identity.subject().strip();
        String email = identity.email().strip().toLowerCase(Locale.ROOT);
        if (subject.length() > 255 || email.length() > 255) {
            throw new BadCredentialsException("Invalid Google identity");
        }
        Optional<AuthAccount> linked = findByGoogleSubject(subject);
        if (linked.isPresent()) {
            return linked.get();
        }

        Optional<GoogleLinkTarget> existing = jdbcTemplate.query("""
                SELECT u.id, r.code AS role, u.status, u.external_id, u.password_hash,
                       EXISTS (SELECT 1 FROM students s WHERE s.user_id = u.id) AS has_student_profile,
                       EXISTS (SELECT 1 FROM advisors a WHERE a.user_id = u.id) AS has_advisor_profile
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.email = ?
                """, (resultSet, rowNumber) -> new GoogleLinkTarget(
                resultSet.getString("id"), resultSet.getString("role"),
                resultSet.getString("status"), resultSet.getString("external_id"),
                resultSet.getString("password_hash"), resultSet.getBoolean("has_student_profile"),
                resultSet.getBoolean("has_advisor_profile")), email).stream().findFirst();

        if (existing.isPresent()) {
            GoogleLinkTarget target = existing.get();
            if (!"ACTIVE".equals(target.status())) {
                throw new DisabledException("Account is not active");
            }
            boolean provisionedAdvisor = "ADVISOR".equals(target.role()) && target.hasAdvisorProfile();
            boolean provisionedStudent = "STUDENT".equals(target.role()) && target.hasStudentProfile();
            if ((!provisionedAdvisor && !provisionedStudent) || target.externalId() != null
                    || target.passwordHash() != null) {
                throw new BadCredentialsException("Google account cannot be linked automatically");
            }
            try {
                int updated = jdbcTemplate.update("""
                        UPDATE users SET auth_provider = 'GOOGLE', external_id = ?,
                            email_verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND external_id IS NULL AND password_hash IS NULL
                          AND status = 'ACTIVE' AND role_id = (SELECT id FROM roles WHERE code = ?)
                        """, subject, target.id(), target.role());
                if (updated == 1) {
                    return findByGoogleSubject(subject).orElseThrow();
                }
            } catch (DuplicateKeyException exception) {
                Optional<AuthAccount> concurrentlyLinked = findByGoogleSubject(subject);
                if (concurrentlyLinked.isPresent()) {
                    return concurrentlyLinked.get();
                }
            }
            throw new BadCredentialsException("Google account is already linked elsewhere");
        }

        UUID userId = UUID.randomUUID();
        String fullName = identity.fullName() == null || identity.fullName().isBlank()
                ? email : identity.fullName().strip();
        try {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO users (id, role_id, email, auth_provider, external_id,
                                       full_name, status, email_verified_at)
                    SELECT ?, r.id, ?, 'GOOGLE', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP
                    FROM roles r WHERE r.code = 'STUDENT'
                    """, userId.toString(), email, subject,
                    fullName.substring(0, Math.min(fullName.length(), 255)));
            if (inserted != 1) {
                throw new IllegalStateException("STUDENT role is missing");
            }
            return findById(userId).orElseThrow();
        } catch (DuplicateKeyException exception) {
            return findByGoogleSubject(subject)
                    .orElseThrow(() -> new BadCredentialsException("Google email is already in use"));
        }
    }

    private Optional<AuthAccount> findByGoogleSubject(String subject) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.auth_provider = 'GOOGLE' AND u.external_id = ?
                """, this::mapAccount, subject).stream().findFirst();
    }

    private AuthAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuthAccount(UUID.fromString(resultSet.getString("id")), resultSet.getString("email"),
                resultSet.getString("full_name"), resultSet.getString("role"), resultSet.getString("status"),
                resultSet.getString("password_hash"));
    }

    private record GoogleLinkTarget(String id, String role, String status,
            String externalId, String passwordHash, boolean hasStudentProfile, boolean hasAdvisorProfile) {
    }
}
