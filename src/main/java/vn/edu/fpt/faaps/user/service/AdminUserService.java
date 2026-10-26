package vn.edu.fpt.faaps.user.service;

import vn.edu.fpt.faaps.user.dto.AdminUserDtos;
import vn.edu.fpt.faaps.user.exception.UserExceptions;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

@Service
public class AdminUserService {
    private final JdbcTemplate jdbc;
    private final AdminUserRepository repository;
    private final UserRoleService roles;

    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.roles = roles;
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (role != null && !List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role is invalid");
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "SUSPENDED", "LOCKED").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return repository.list(role, status, search, majorId, page, size, sort);
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
        if (input.phone() != null && input.phone().length() > 20) {
            throw new IllegalArgumentException("phone must be at most 20 characters");
        }

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
            saveUser(userId, email, role, input, completeGoogleStudent);
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

    private void saveUser(UUID userId, String email, String role, CreateAccount input, boolean existingGoogleStudent) {
        if (existingGoogleStudent) {
            jdbc.update("UPDATE users SET full_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    input.fullName().strip(), input.phone(), userId.toString());
            return;
        }
        int inserted = jdbc.update("""
                        INSERT INTO users (id, role_id, email, auth_provider, full_name, phone, status,
                                           created_at, updated_at)
                        SELECT ?, r.id, ?, 'GOOGLE', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        FROM roles r WHERE r.code = ?
                        """, userId.toString(), email, input.fullName().strip(), input.phone(), role);
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
        if (input.studentCode() == null || input.studentCode().isBlank() || input.studentCode().length() > 50) {
            throw new IllegalArgumentException("studentCode is required and must be at most 50 characters");
        }
        if (input.curriculumVersionId() == null || input.enrollmentSemesterId() == null) {
            throw new IllegalArgumentException("curriculumVersionId and enrollmentSemesterId are required for a Student");
        }
        if (input.currentSemester() != null && input.currentSemester() < 1) {
            throw new IllegalArgumentException("currentSemester must be at least 1");
        }
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

        LocalDate enrollmentDate = semesterStart(input.enrollmentSemesterId(), "Enrollment semester");
        if (input.expectedGraduationSemesterId() != null) {
            LocalDate expectedDate = semesterStart(input.expectedGraduationSemesterId(), "Expected graduation semester");
            if (expectedDate.isBefore(enrollmentDate)) {
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
        String majorId = jdbc.query("SELECT major_id FROM curriculum_versions WHERE id = ?",
                (rs, rowNum) -> rs.getString("major_id"), input.curriculumVersionId().toString())
                .stream().findFirst().orElseThrow(() -> new NotFound("Curriculum not found"));
        jdbc.update("""
                INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
                                      enrollment_semester_id, expected_graduation_semester_id,
                                      current_semester, current_gpa, total_credits_earned, academic_status,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId.toString(), input.studentCode().strip(), majorId,
                input.curriculumVersionId().toString(), input.enrollmentSemesterId().toString(),
                input.expectedGraduationSemesterId() == null ? null : input.expectedGraduationSemesterId().toString(),
                input.currentSemester() == null ? 1 : input.currentSemester());
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

    private record ExistingAccount(UUID id, String role, String externalId, String status,
            boolean hasStudentProfile) {
    }

}
