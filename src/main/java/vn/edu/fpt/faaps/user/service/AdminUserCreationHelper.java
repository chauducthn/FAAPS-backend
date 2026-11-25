package vn.edu.fpt.faaps.user.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.common.validation.IdentityInput;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

final class AdminUserCreationHelper {

    private static final Logger log = LoggerFactory.getLogger(AdminUserCreationHelper.class);

    private final JdbcTemplate jdbc;
    private final AdminUserInputValidator validator;

    AdminUserCreationHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.validator = new AdminUserInputValidator(jdbc);
    }

    UUID createAccount(CreateAccount input) {
        if (input == null) {
            throw new IllegalArgumentException("account is required");
        }
        String role = input.role() == null ? "" : input.role().strip().toUpperCase(Locale.ROOT);
        if (!List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        }
        validator.requireText(input.fullName(), "fullName", 255);
        String phone = validator.normalizePhone(input.phone());
        String email = IdentityInput.email(input.email());

        ExistingAccount existing = findByEmailForUpdate(email);
        boolean completeGoogleStudent = existing != null && "STUDENT".equals(role)
                && "STUDENT".equals(existing.role()) && existing.externalId() != null
                && !existing.hasStudentProfile() && "ACTIVE".equals(existing.status());
        if (existing != null && !completeGoogleStudent) {
            throw new Conflict("An account with this email already exists");
        }
        if ("STUDENT".equals(role)) {
            validator.validateStudentInput(input);
        } else {
            validator.validateAdvisorInput(input);
        }
        UUID userId = completeGoogleStudent ? existing.id() : UUID.randomUUID();
        try {
            saveUser(userId, email, role, input, phone, completeGoogleStudent);
            if ("STUDENT".equals(role)) {
                addStudentProfile(userId, input);
            } else {
                saveAdvisorProfile(userId, input.maxAdvisees(), input.advisorCode());
            }
        } catch (DuplicateKeyException exception) {
            throw new Conflict("Email or student code already exists");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Account references invalid academic data or conflicts with an existing record");
        }
        return userId;
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

    private void saveAdvisorProfile(UUID userId, Integer requestedMaxAdvisees, String advisorCode) {
        int maxAdvisees = requestedMaxAdvisees == null ? 30 : requestedMaxAdvisees;
        String cleanCode = advisorCode == null || advisorCode.isBlank()
                ? null : advisorCode.strip().toUpperCase(Locale.ROOT);
        if (cleanCode != null) {
            if (cleanCode.length() > 50) {
                throw new IllegalArgumentException("advisorCode must be at most 50 characters");
            }
            try {
                jdbc.update("""
                        INSERT INTO advisors (user_id, advisor_code, max_advisees, status, created_at, updated_at)
                        VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, userId.toString(), cleanCode, maxAdvisees);
                return;
            } catch (DuplicateKeyException duplicate) {
                throw duplicate;
            } catch (BadSqlGrammarException ex) {
                log.debug("Fallback advisor insert without advisor_code column: {}", ex.getMessage());
            }
        }
        jdbc.update("""
                INSERT INTO advisors (user_id, max_advisees, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId.toString(), maxAdvisees);
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
                        ? ExpectedGraduation.semesterId(jdbc, input.curriculumVersionId(),
                                input.enrollmentSemesterId(), currentSemester)
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

    private record ExistingAccount(UUID id, String role, String externalId, String status,
            boolean hasStudentProfile) {}
}
