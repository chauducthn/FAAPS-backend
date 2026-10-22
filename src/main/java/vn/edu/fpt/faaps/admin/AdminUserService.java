package vn.edu.fpt.faaps.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private final JdbcTemplate jdbc;

    public AdminUserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (role != null && !List.of("STUDENT", "ADVISOR", "ADMIN").contains(role)) {
            throw new IllegalArgumentException("role is invalid");
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "SUSPENDED", "LOCKED").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        String order = switch (sort) {
            case "createdAt,asc" -> "u.created_at ASC, u.id ASC";
            case "createdAt,desc" -> "u.created_at DESC, u.id DESC";
            case "fullName,asc" -> "u.full_name ASC, u.id ASC";
            case "fullName,desc" -> "u.full_name DESC, u.id DESC";
            case "userId,asc" -> "COALESCE(s.student_code, u.email) ASC, u.id ASC";
            case "userId,desc" -> "COALESCE(s.student_code, u.email) DESC, u.id DESC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (role != null) {
            where.append(" AND r.code = ?");
            parameters.add(role);
        }
        if (status != null) {
            where.append(" AND u.status = ?");
            parameters.add(status);
        }
        if (majorId != null) {
            where.append(" AND s.major_id = ?");
            parameters.add(majorId.toString());
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(s.student_code) LIKE ?)");
            for (int index = 0; index < 3; index++) {
                parameters.add(term);
            }
        }
        String from = " FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN students s ON s.user_id = u.id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(page * size);
        List<Summary> items = jdbc.query("SELECT u.id, COALESCE(s.student_code, u.email) AS user_code, u.full_name, u.email, r.code AS role, u.status, u.created_at" + from + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", this::summary, pageParameters.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Detail detail(UUID id) {
        return jdbc.query("""
                SELECT u.id, COALESCE(s.student_code, u.email) AS user_code,
                       u.full_name, u.email, u.phone, r.code AS role, u.status,
                       u.created_at, u.updated_at, s.major_id, m.name AS major_name,
                       s.curriculum_version_id, cv.version AS curriculum_code,
                       s.enrollment_semester_id, s.expected_graduation_semester_id,
                       s.current_semester, s.academic_status
                FROM users u JOIN roles r ON r.id = u.role_id
                LEFT JOIN students s ON s.user_id = u.id
                LEFT JOIN majors m ON m.id = s.major_id
                LEFT JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                WHERE u.id = ?
                """, (result, row) -> new Detail(
                UUID.fromString(result.getString("id")), result.getString("user_code"),
                result.getString("full_name"), result.getString("email"), result.getString("phone"),
                result.getString("role"), result.getString("status"),
                result.getTimestamp("created_at").toLocalDateTime(),
                result.getTimestamp("updated_at").toLocalDateTime(),
                result.getString("major_id"), result.getString("major_name"),
                result.getString("curriculum_version_id"), result.getString("curriculum_code"),
                result.getString("enrollment_semester_id"), result.getString("expected_graduation_semester_id"),
                (Integer) result.getObject("current_semester"), result.getString("academic_status")),
                id.toString()).stream().findFirst().orElseThrow(NotFound::new);
    }

    @Transactional
    public Detail create(CreateAccount input) {
        String role = input.role() == null ? "" : input.role().strip().toUpperCase(Locale.ROOT);
        if (!List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        }
        if (input.email() == null || input.email().isBlank() || input.email().length() > 255) {
            throw new IllegalArgumentException("email is required and must be at most 255 characters");
        }
        if (input.fullName() == null || input.fullName().isBlank() || input.fullName().length() > 255) {
            throw new IllegalArgumentException("fullName is required and must be at most 255 characters");
        }
        if (input.phone() != null && input.phone().length() > 20) {
            throw new IllegalArgumentException("phone must be at most 20 characters");
        }

        String email = input.email().strip().toLowerCase(Locale.ROOT);
        UUID userId = null;
        boolean completeGoogleStudent = false;
        List<ExistingAccount> existing = jdbc.query("""
                SELECT u.id, r.code AS role, u.external_id, u.status,
                       EXISTS (SELECT 1 FROM students s WHERE s.user_id = u.id) AS has_student_profile
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE LOWER(u.email) = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ExistingAccount(UUID.fromString(rs.getString("id")),
                rs.getString("role"), rs.getString("external_id"), rs.getString("status"),
                rs.getBoolean("has_student_profile")), email);
        if (!existing.isEmpty()) {
            ExistingAccount account = existing.get(0);
            completeGoogleStudent = "STUDENT".equals(role) && "STUDENT".equals(account.role())
                    && account.externalId() != null && !account.hasStudentProfile()
                    && "ACTIVE".equals(account.status());
            if (!completeGoogleStudent) {
                throw new Conflict("An account with this email already exists");
            }
            userId = account.id();
        }

        if ("STUDENT".equals(role)) {
            validateStudentInput(input);
        } else {
            validateAdvisorInput(input);
        }

        UUID resolvedUserId = userId == null ? UUID.randomUUID() : userId;
        try {
            if (!completeGoogleStudent) {
                Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE code = ?", Integer.class, role);
                if (roleCount == null || roleCount == 0) {
                    throw new Conflict("Role " + role + " is not configured");
                }
                int inserted = jdbc.update("""
                        INSERT INTO users (id, role_id, email, auth_provider, full_name, phone, status,
                                           created_at, updated_at)
                        SELECT ?, r.id, ?, 'GOOGLE', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        FROM roles r WHERE r.code = ?
                        """, resolvedUserId.toString(), email, input.fullName().strip(), input.phone(), role);
                if (inserted != 1) {
                    throw new Conflict("Role " + role + " is not configured");
                }
            } else {
                jdbc.update("UPDATE users SET full_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        input.fullName().strip(), input.phone(), resolvedUserId.toString());
            }

            if ("STUDENT".equals(role)) {
                addStudentProfile(resolvedUserId, input);
            } else {
                int maxAdvisees = input.maxAdvisees() == null ? 30 : input.maxAdvisees();
                jdbc.update("""
                        INSERT INTO advisors (user_id, max_advisees, status, created_at, updated_at)
                        VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, resolvedUserId.toString(), maxAdvisees);
            }
        } catch (DuplicateKeyException exception) {
            throw new Conflict("Email or student code already exists");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Account references invalid academic data or conflicts with an existing record");
        }
        return detail(resolvedUserId);
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
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        }
        if (userIds.size() > 100 || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("userIds must be unique and contain at most 100 users");
        }
        if ("SUSPENDED".equals(status) && userIds.contains(actorId)) {
            throw new Conflict();
        }
        int changed = 0;
        for (UUID id : userIds) {
            List<String> current = jdbc.query("SELECT status FROM users WHERE id = ? FOR UPDATE",
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

    private Summary summary(ResultSet result, int row) throws SQLException {
        return new Summary(UUID.fromString(result.getString("id")), result.getString("user_code"),
                result.getString("full_name"), result.getString("email"),
                result.getString("role"), result.getString("status"),
                result.getTimestamp("created_at").toLocalDateTime());
    }

    public record Summary(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt) {
    }

    public record Detail(UUID id, String userId, String fullName, String email, String phone,
            String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            String majorId, String majorName, String curriculumVersionId, String curriculumCode,
            String enrollmentSemesterId, String expectedGraduationSemesterId,
            Integer currentSemester, String academicStatus) {
    }

    public record CreateAccount(String role, String email, String fullName, String phone,
            String studentCode, UUID curriculumVersionId, UUID enrollmentSemesterId,
            UUID expectedGraduationSemesterId, Integer currentSemester, Integer maxAdvisees) {
    }

    private record ExistingAccount(UUID id, String role, String externalId, String status,
            boolean hasStudentProfile) {
    }

    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
        public NotFound() {
        }

        public NotFound(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict() {
        }

        public Conflict(String message) {
            super(message);
        }
    }
}
