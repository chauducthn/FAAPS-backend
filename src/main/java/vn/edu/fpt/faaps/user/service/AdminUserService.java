package vn.edu.fpt.faaps.user.service;

import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos;
import vn.edu.fpt.faaps.user.exception.UserExceptions;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetEntry;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetResult;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

@Service
public class AdminUserService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AdminUserRepository repository;
    private final UserRoleService roles;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles,
            PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles) {
        this(jdbc, repository, roles, new BCryptPasswordEncoder());
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        return list(role, status, search, majorId, null, page, size, sort);
    }

    public Page list(String role, String status, String search, UUID majorId, UUID curriculumVersionId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (role != null && !List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role is invalid");
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "SUSPENDED", "LOCKED").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return repository.list(role, status, search, majorId, curriculumVersionId, page, size, sort);
    }

    public Detail detail(UUID id) {
        return repository.detail(id);
    }

    @Transactional
    public Detail create(CreateAccount input) {
        if (input == null) {
            throw new IllegalArgumentException("account is required");
        }
        String role = input.role() == null ? "" : input.role().strip().toUpperCase(Locale.ROOT);
        if (!List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        }
        requireText(input.email(), "email", 255);
        requireText(input.fullName(), "fullName", 255);
        String phone = normalizePhone(input.phone());

        String email = input.email().strip().toLowerCase(Locale.ROOT);
        ExistingAccount existing = findByEmailForUpdate(email);
        boolean completeGoogleStudent = existing != null && "STUDENT".equals(role)
                && "STUDENT".equals(existing.role()) && existing.externalId() != null
                && !existing.hasStudentProfile() && "ACTIVE".equals(existing.status());
        if (existing != null && !completeGoogleStudent) {
            throw new Conflict("An account with this email already exists");
        }
        if ("STUDENT".equals(role)) {
            validateStudentInput(input);
        } else {
            validateAdvisorInput(input);
        }
        UUID userId = completeGoogleStudent ? existing.id() : UUID.randomUUID();
        try {
            saveUser(userId, email, role, input, phone, completeGoogleStudent);
            if ("STUDENT".equals(role)) {
                addStudentProfile(userId, input);
            } else {
                saveAdvisorProfile(userId, input.maxAdvisees());
            }
        } catch (DuplicateKeyException exception) {
            throw new Conflict("Email or student code already exists");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Account references invalid academic data or conflicts with an existing record");
        }
        return detail(userId);
    }

    private ExistingAccount findByEmailForUpdate(String email) {
        return jdbc.query("""
                SELECT u.id, r.code AS role, u.external_id, u.status,
                       EXISTS (SELECT 1 FROM students s WHERE s.user_id = u.id) AS has_student_profile
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE LOWER(u.email) = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ExistingAccount(UUID.fromString(rs.getString("id")),
                rs.getString("role"), rs.getString("external_id"), rs.getString("status"),
                rs.getBoolean("has_student_profile")), email).stream().findFirst().orElse(null);
    }

    /** A blank phone is allowed (it stays empty); a given one must be a Vietnamese mobile number. */
    private static String normalizePhone(String phone) {
        String clean = phone == null || phone.isBlank() ? null : phone.strip();
        if (clean != null && !StudentOnboardingService.PHONE.matcher(clean).matches()) {
            throw new IllegalArgumentException("phone must be a 10-digit Vietnamese number starting with 0 (e.g. 0901234567)");
        }
        return clean;
    }

    private void saveUser(UUID userId, String email, String role, CreateAccount input, String phone,
            boolean existingGoogleStudent) {
        if (existingGoogleStudent) {
            jdbc.update("UPDATE users SET full_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    input.fullName().strip(), phone, userId.toString());
            return;
        }
        int inserted = jdbc.update("""
                        INSERT INTO users (id, role_id, email, auth_provider, full_name, phone, status,
                                           created_at, updated_at)
                        SELECT ?, r.id, ?, 'GOOGLE', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        FROM roles r WHERE r.code = ?
                        """, userId.toString(), email, input.fullName().strip(), phone, role);
        if (inserted != 1) {
            throw new Conflict("Role " + role + " is not configured");
        }
    }

    private void saveAdvisorProfile(UUID userId, Integer requestedMaxAdvisees) {
        int maxAdvisees = requestedMaxAdvisees == null ? 30 : requestedMaxAdvisees;
        jdbc.update("""
                        INSERT INTO advisors (user_id, max_advisees, status, created_at, updated_at)
                        VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, userId.toString(), maxAdvisees);
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must be at most " + maxLength + " characters");
        }
    }

    private void validateStudentInput(CreateAccount input) {
        StudentCodeValidator.validateAndNormalize(input.studentCode());
        if (input.currentSemester() != null && input.currentSemester() < 1) {
            throw new IllegalArgumentException("currentSemester must be at least 1");
        }
        if (input.curriculumVersionId() != null) {
            List<String> curriculumStatus = jdbc.query("""
                    SELECT cv.status
                    FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                    WHERE cv.id = ? AND m.status = 'ACTIVE'
                    """, (rs, rowNum) -> rs.getString("status"), input.curriculumVersionId().toString());
            if (curriculumStatus.isEmpty()) {
                throw new NotFound("Curriculum or its major was not found");
            }
            if (!"ACTIVE".equalsIgnoreCase(curriculumStatus.get(0))) {
                throw new Conflict("Student must be enrolled in an active curriculum");
            }
        }

        LocalDate enrollmentDate = input.enrollmentSemesterId() != null
                ? semesterStart(input.enrollmentSemesterId(), "Enrollment semester")
                : null;
        if (input.expectedGraduationSemesterId() != null) {
            LocalDate expectedDate = semesterStart(input.expectedGraduationSemesterId(), "Expected graduation semester");
            if (enrollmentDate != null && expectedDate.isBefore(enrollmentDate)) {
                throw new IllegalArgumentException("expectedGraduationSemesterId cannot be before enrollmentSemesterId");
            }
        }
    }

    private void validateAdvisorInput(CreateAccount input) {
        if (input.maxAdvisees() != null && input.maxAdvisees() < 1) {
            throw new IllegalArgumentException("maxAdvisees must be at least 1");
        }
    }

    private void addStudentProfile(UUID userId, CreateAccount input) {
        String normalizedCode = StudentCodeValidator.validateAndNormalize(input.studentCode());
        String majorId = null;
        if (input.curriculumVersionId() != null) {
            majorId = jdbc.query("SELECT major_id FROM curriculum_versions WHERE id = ?",
                    (rs, rowNum) -> rs.getString("major_id"), input.curriculumVersionId().toString())
                    .stream().findFirst().orElseThrow(() -> new NotFound("Curriculum not found"));
        }
        int currentSemester = input.currentSemester() == null ? 1 : input.currentSemester();
        String expectedGraduationId = input.expectedGraduationSemesterId() != null
                ? input.expectedGraduationSemesterId().toString()
                : (input.curriculumVersionId() != null && input.enrollmentSemesterId() != null
                    ? ExpectedGraduation.semesterId(jdbc, input.curriculumVersionId(), input.enrollmentSemesterId(), currentSemester)
                    : null);

        jdbc.update("""
                INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
                                      enrollment_semester_id, expected_graduation_semester_id,
                                      current_semester, current_gpa, total_credits_earned, academic_status,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId.toString(), normalizedCode, majorId,
                input.curriculumVersionId() == null ? null : input.curriculumVersionId().toString(),
                input.enrollmentSemesterId() == null ? null : input.enrollmentSemesterId().toString(),
                expectedGraduationId, currentSemester);
    }

    private LocalDate semesterStart(UUID semesterId, String label) {
        return jdbc.query("SELECT start_date FROM semesters WHERE id = ?",
                (rs, rowNum) -> rs.getDate("start_date").toLocalDate(), semesterId.toString())
                .stream().findFirst().orElseThrow(() -> new NotFound(label + " was not found"));
    }

    @Transactional
    public int changeStatus(List<UUID> userIds, String status, UUID actorId) {
        if (status == null || !List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        }
        if (userIds == null || userIds.isEmpty() || userIds.size() > 100
                || userIds.stream().anyMatch(java.util.Objects::isNull)
                || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("userIds must contain 1 to 100 unique, non-null users");
        }
        if ("SUSPENDED".equals(status) && userIds.contains(actorId)) {
            throw new Conflict();
        }
        int changed = 0;
        for (UUID id : userIds.stream().sorted().toList()) {
            List<String> current = jdbc.query("""
                    SELECT u.status FROM users u JOIN roles r ON r.id = u.role_id
                    WHERE u.id = ? AND r.code IN ('STUDENT', 'ADVISOR') FOR UPDATE
                    """,
                    (result, row) -> result.getString(1), id.toString());
            if (current.isEmpty()) {
                throw new NotFound();
            }
            if (status.equals(current.get(0))) {
                continue;
            }
            if (!("ACTIVE".equals(current.get(0)) && "SUSPENDED".equals(status))
                    && !("SUSPENDED".equals(current.get(0)) && "ACTIVE".equals(status))) {
                throw new Conflict();
            }
            changed += jdbc.update("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    status, id.toString());
        }
        return changed;
    }

    @Transactional
    public Detail changeRole(UUID userId, ChangeRole input) {
        return roles.change(userId, input);
    }

    @Transactional
    public PasswordResetResponse resetPassword(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return resetPasswords(List.of(id));
    }

    @Transactional
    public PasswordResetResponse bulkResetPassword(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > 100
                || userIds.stream().anyMatch(java.util.Objects::isNull)
                || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("userIds must contain 1 to 100 unique, non-null users");
        }
        return resetPasswords(userIds);
    }

    private PasswordResetResponse resetPasswords(List<UUID> userIds) {
        List<PasswordResetEntry> resetResults = new ArrayList<>();
        Set<String> generatedPasswords = new HashSet<>();
        int changed = 0;
        String firstPassword = null;

        for (UUID id : userIds.stream().sorted().toList()) {
            List<ResetTarget> targets = jdbc.query("""
                    SELECT u.id,
                           CASE WHEN r.code = 'STUDENT' THEN COALESCE(s.student_code, u.email) ELSE u.email END AS user_code,
                           u.email,
                           u.full_name,
                           u.status
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
                            rs.getString("status")
                    ), id.toString());

            if (targets.isEmpty()) {
                throw new NotFound("User " + id + " was not found");
            }

            ResetTarget target = targets.get(0);

            // Generate a unique random password for EACH account in the batch
            String newPassword;
            do {
                newPassword = generateRandomPassword();
            } while (!generatedPasswords.add(newPassword));

            if (firstPassword == null) {
                firstPassword = newPassword;
            }

            String passwordHash = passwordEncoder.encode(newPassword);

            int rows = jdbc.update("""
                    UPDATE users
                    SET password_hash = ?,
                        auth_provider = 'LOCAL',
                        failed_login_attempts = 0, locked_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, passwordHash, id.toString());

            changed += rows;

            try {
                jdbc.update("""
                        INSERT INTO audit_logs (id, user_id, action, entity_type, entity_id, created_at)
                        VALUES (?, ?, 'RESET_PASSWORD', 'USER', ?, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID().toString(), id.toString(), id.toString());
            } catch (Exception ignored) {
                // Non-blocking audit trail fallback when audit_logs table is unavailable
            }

            // =========================================================================================
            // [OPTIONAL EXTENSION HOOK]: Email Sender / Notification Dispatcher
            // -----------------------------------------------------------------------------------------
            // Reserved for future email dispatch (e.g. sending new credentials / reset alert to user):
            // e.g. emailSender.sendPasswordReset(target.email(), target.fullName(), newPassword);
            // In case user lost email access, Admin retains this password on screen / CSV to hand over.
            // =========================================================================================
            dispatchPasswordResetNotification(target.email(), target.fullName(), newPassword);

            resetResults.add(new PasswordResetEntry(
                    target.userCode(),
                    target.email(),
                    target.fullName(),
                    "SUCCESS",
                    newPassword,
                    null
            ));
        }

        return new PasswordResetResponse(changed, firstPassword, resetResults);
    }

    /**
     * Optional extension point: dispatch password reset notification via email.
     * Currently a no-op placeholder until mail server configuration is wired up.
     */
    private void dispatchPasswordResetNotification(String email, String fullName, String newPassword) {
        // [EXTENSION HOOK]: Send email alert with new password or password reset notification
        // e.g. emailSender.sendResetAlert(email, fullName, newPassword);
    }

    private static String generateRandomPassword() {
        return "Faaps@" + (100000 + RANDOM.nextInt(900000));
    }

    private record ResetTarget(UUID id, String userCode, String email, String fullName, String status) {
    }

    private record ExistingAccount(UUID id, String role, String externalId, String status,
            boolean hasStudentProfile) {
    }

}
