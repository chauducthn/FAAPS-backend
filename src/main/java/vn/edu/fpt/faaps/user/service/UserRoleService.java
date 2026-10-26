package vn.edu.fpt.faaps.user.service;

import vn.edu.fpt.faaps.user.dto.AdminUserDtos;
import vn.edu.fpt.faaps.user.exception.UserExceptions;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

@Service
public class UserRoleService {
    private final JdbcTemplate jdbc;
    private final AdminUserRepository users;

    public UserRoleService(JdbcTemplate jdbc, AdminUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public Detail change(UUID userId, ChangeRole input) {
        if (input == null || !List.of("STUDENT", "ADVISOR").contains(input.role()))
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        String current = jdbc.query("""
                SELECT r.code FROM users u JOIN roles r ON r.id=u.role_id
                WHERE u.id=? AND r.code IN ('STUDENT','ADVISOR') FOR UPDATE
                """, (rs, row) -> rs.getString(1), userId.toString()).stream()
                .findFirst().orElseThrow(NotFound::new);
        if (current.equals(input.role())) return users.detail(userId);
        if ("ADVISOR".equals(input.role())) toAdvisor(userId, input.maxAdvisees());
        else toStudent(userId, input);
        int updated = jdbc.update("""
                UPDATE users SET role_id=(SELECT id FROM roles WHERE code=?), updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, input.role(), userId.toString());
        if (updated != 1) throw new Conflict("Target role is not configured");
        return users.detail(userId);
    }

    private void toAdvisor(UUID userId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 30 : requestedLimit;
        if (limit < 1) throw new IllegalArgumentException("maxAdvisees must be at least 1");
        jdbc.update("""
                INSERT INTO advisors (user_id,max_advisees,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE max_advisees=VALUES(max_advisees),status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                """, userId.toString(), limit);
        jdbc.update("""
                UPDATE student_advisor_assignments SET status='COMPLETED',end_date=CURRENT_DATE,updated_at=CURRENT_TIMESTAMP
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
        if (active != null && active > 0)
            throw new Conflict("Reassign active students before changing this Advisor to Student");
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?",
                Integer.class, userId.toString());
        if (existing == null || existing == 0) createStudentProfile(userId, input);
        else jdbc.update("UPDATE students SET academic_status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                userId.toString());
        jdbc.update("UPDATE advisors SET status='INACTIVE',updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                userId.toString());
        jdbc.update("DELETE FROM cohort_advisors WHERE advisor_id=?", userId.toString());
    }

    private void createStudentProfile(UUID userId, ChangeRole input) {
        String code = input.studentCode() == null ? "" : input.studentCode().strip().toUpperCase(Locale.ROOT);
        if (!code.matches("^(SE|IA)1\\d{5}$"))
            throw new IllegalArgumentException("studentCode must match SE1##### or IA1#####");
        if (input.curriculumVersionId() == null || input.enrollmentSemesterId() == null)
            throw new IllegalArgumentException("curriculumVersionId and enrollmentSemesterId are required");
        String majorId = jdbc.query("SELECT major_id FROM curriculum_versions WHERE id=? AND status='ACTIVE'",
                (rs, row) -> rs.getString(1), input.curriculumVersionId().toString()).stream()
                .findFirst().orElseThrow(() -> new NotFound("Active curriculum not found"));
        semesterStart(input.enrollmentSemesterId());
        jdbc.update("""
                INSERT INTO students (user_id,student_code,major_id,curriculum_version_id,enrollment_semester_id,
                                      current_semester,academic_status,created_at,updated_at)
                VALUES (?,?,?,?,?,1,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, userId.toString(), code, majorId, input.curriculumVersionId().toString(),
                input.enrollmentSemesterId().toString());
    }

    private LocalDate semesterStart(UUID id) {
        return jdbc.query("SELECT start_date FROM semesters WHERE id=?",
                (rs, row) -> rs.getDate(1).toLocalDate(), id.toString()).stream()
                .findFirst().orElseThrow(() -> new NotFound("Enrollment semester was not found"));
    }
}
