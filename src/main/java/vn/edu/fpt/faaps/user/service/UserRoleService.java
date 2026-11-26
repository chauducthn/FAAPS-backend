package vn.edu.fpt.faaps.user.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.EmailCheckItem;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;

@Service
public class UserRoleService {

    private static final Logger log = LoggerFactory.getLogger(UserRoleService.class);

    private final JdbcTemplate jdbc;
    private final AdminUserRepository users;
    private final UserRoleEmailHelper emailHelper;
    private final UserBulkRoleHelper bulkHelper;

    public UserRoleService(JdbcTemplate jdbc, AdminUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
        this.emailHelper = new UserRoleEmailHelper(jdbc);
        this.bulkHelper = new UserBulkRoleHelper(this);
    }

    public Map<String, Boolean> checkEmailAvailability(List<EmailCheckItem> items) {
        return emailHelper.checkEmailAvailability(items);
    }

    @Transactional
    public BulkRoleResponse bulkChange(String targetRole, List<BulkRoleItem> items) {
        return bulkHelper.bulkChange(targetRole, items);
    }

    @Transactional
    public Detail change(UUID userId, ChangeRole input) {
        if (input == null || !List.of("STUDENT", "ADVISOR").contains(input.role())) {
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        }
        String current = jdbc.query("""
                SELECT r.code FROM users u JOIN roles r ON r.id=u.role_id
                WHERE u.id=? AND r.code IN ('STUDENT','ADVISOR') FOR UPDATE
                """, (rs, row) -> rs.getString(1), userId.toString()).stream()
                .findFirst().orElseThrow(NotFound::new);
        String newEmail = emailHelper.validateReplacementEmail(userId, input.email(), input.role());
        if (current.equals(input.role()) && newEmail == null) {
            return users.detail(userId);
        }
        if (!current.equals(input.role())) {
            if ("ADVISOR".equals(input.role())) {
                String advisorEmail = newEmail != null ? newEmail
                        : jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId.toString());
                toAdvisor(userId, input.maxAdvisees(), input.advisorCode(), advisorEmail);
            } else {
                toStudent(userId, input);
            }
        }
        int updated = newEmail != null
                ? jdbc.update("""
                        UPDATE users
                        SET role_id=(SELECT id FROM roles WHERE code=?), email=?, external_id=NULL,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE id=?
                        """, input.role(), newEmail, userId.toString())
                : jdbc.update("""
                        UPDATE users SET role_id=(SELECT id FROM roles WHERE code=?), updated_at=CURRENT_TIMESTAMP
                        WHERE id=?
                        """, input.role(), userId.toString());
        if (updated != 1) {
            throw new Conflict("Target role is not configured");
        }
        return users.detail(userId);
    }

    private void toAdvisor(UUID userId, Integer requestedLimit, String advisorCode, String email) {
        int limit = requestedLimit == null ? 30 : requestedLimit;
        if (limit < 1) {
            throw new IllegalArgumentException("maxAdvisees must be at least 1");
        }
        int at = email == null ? -1 : email.indexOf('@');
        String derived = email != null && at > 0 ? email.substring(0, at).strip() : null;
        String cleanCode = advisorCode == null || advisorCode.isBlank() ? derived : advisorCode.strip();
        boolean insertedWithCode = false;
        if (cleanCode != null) {
            try {
                jdbc.update("""
                        INSERT INTO advisors (user_id,advisor_code,max_advisees,status,created_at,updated_at)
                        VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        ON DUPLICATE KEY UPDATE advisor_code=VALUES(advisor_code),
                            max_advisees=VALUES(max_advisees),status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                        """, userId.toString(), cleanCode, limit);
                insertedWithCode = true;
            } catch (BadSqlGrammarException ex) {
                log.debug("Falling back to advisor upsert without advisor_code column: {}", ex.getMessage());
            }
        }
        if (!insertedWithCode) {
            jdbc.update("""
                    INSERT INTO advisors (user_id,max_advisees,status,created_at,updated_at)
                    VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE max_advisees=VALUES(max_advisees),
                        status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                    """, userId.toString(), limit);
        }
        jdbc.update("""
                UPDATE student_advisor_assignments
                SET status='COMPLETED',end_date=CURRENT_DATE,updated_at=CURRENT_TIMESTAMP
                WHERE student_id=? AND status='ACTIVE' AND end_date IS NULL
                """, userId.toString());
        jdbc.update("UPDATE students SET academic_status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                userId.toString());
    }

    private void toStudent(UUID userId, ChangeRole input) {
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE advisor_id=? AND status='ACTIVE' AND end_date IS NULL
                """, Integer.class, userId.toString());
        if (active != null && active > 0) {
            throw new Conflict("Reassign active students before changing this Advisor to Student");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?",
                Integer.class, userId.toString());
        if (existing == null || existing == 0) {
            createStudentProfile(userId, input);
        } else {
            jdbc.update("UPDATE students SET academic_status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                    userId.toString());
        }
        jdbc.update("UPDATE advisors SET status='INACTIVE',updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                userId.toString());
        jdbc.update("DELETE FROM cohort_advisors WHERE advisor_id=?", userId.toString());
    }

    private void createStudentProfile(UUID userId, ChangeRole input) {
        String code = StudentCodeValidator.validateAndNormalize(input.studentCode());
        String majorId = null;
        if (input.curriculumVersionId() != null) {
            var cohortMajor = jdbc.query(
                    "SELECT cv.major_id, m.code AS major_code, cv.version FROM curriculum_versions cv "
                            + "JOIN majors m ON m.id = cv.major_id WHERE cv.id=? AND cv.status='ACTIVE'",
                    (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                    input.curriculumVersionId().toString()).stream()
                    .findFirst().orElseThrow(() -> new NotFound("Active curriculum not found"));
            majorId = cohortMajor[0];
            StudentCodeValidator.validateMajorAndCohortMatch(code, cohortMajor[1], cohortMajor[2]);
        }
        String expectedGraduationId = null;
        if (input.enrollmentSemesterId() != null) {
            semesterStart(input.enrollmentSemesterId());
            if (input.curriculumVersionId() != null) {
                expectedGraduationId = ExpectedGraduation.semesterId(jdbc, input.curriculumVersionId(),
                        input.enrollmentSemesterId(), 1);
            }
        }
        jdbc.update("""
                INSERT INTO students (user_id,student_code,major_id,curriculum_version_id,enrollment_semester_id,
                                      expected_graduation_semester_id,current_semester,academic_status,created_at,
                                      updated_at)
                VALUES (?,?,?,?,?,?,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, userId.toString(), code, majorId,
                input.curriculumVersionId() == null ? null : input.curriculumVersionId().toString(),
                input.enrollmentSemesterId() == null ? null : input.enrollmentSemesterId().toString(),
                expectedGraduationId);
    }

    private LocalDate semesterStart(UUID id) {
        return jdbc.query("SELECT start_date FROM semesters WHERE id=?",
                (rs, row) -> rs.getDate(1).toLocalDate(), id.toString()).stream()
                .findFirst().orElseThrow(() -> new NotFound("Enrollment semester was not found"));
    }
}
