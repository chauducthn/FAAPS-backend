package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.*;

final class StudentAcademicQueryRepository {
    private static final Logger log = LoggerFactory.getLogger(StudentAcademicQueryRepository.class);

    private final JdbcTemplate jdbc;
    private final StudentCatalogQueryRepository catalog;

    StudentAcademicQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.catalog = new StudentCatalogQueryRepository(jdbc);
    }

    Profile profile(UUID studentId) {
        try {
            return profile(studentId, true);
        } catch (BadSqlGrammarException ex) {
            log.debug("Falling back to student profile query without major groups: {}", ex.getMessage());
            return profile(studentId, false);
        }
    }

    private Profile profile(UUID studentId, boolean withMajorGroups) {
        String groupColumns = withMajorGroups
                ? "m.major_group_id, mg.code AS major_group_code, mg.name AS major_group_name,"
                : "NULL AS major_group_id, NULL AS major_group_code, NULL AS major_group_name,";
        String groupJoin = withMajorGroups ? "LEFT JOIN major_groups mg ON mg.id = m.major_group_id" : "";
        return jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.phone, u.date_of_birth, u.status AS account_status,
                       s.student_code, s.major_id, m.code AS major_code, m.name AS major_name, %s
                       s.curriculum_version_id, cv.version AS cohort, s.current_semester,
                       s.current_gpa, s.total_credits_earned, s.academic_status
                FROM users u LEFT JOIN students s ON s.user_id = u.id
                LEFT JOIN majors m ON m.id = s.major_id %s
                LEFT JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                WHERE u.id = ?
                """.formatted(groupColumns, groupJoin), (rs, row) -> new Profile(rs.getString("id"),
                rs.getString("full_name"), rs.getString("email"),
                rs.getString("phone") == null ? null : rs.getString("phone").replaceAll("\\s+", ""),
                rs.getString("account_status"),
                rs.getString("student_code"), rs.getString("major_id"), rs.getString("major_code"),
                rs.getString("major_name"), rs.getString("major_group_id"), rs.getString("major_group_code"),
                rs.getString("major_group_name"), rs.getString("curriculum_version_id"),
                rs.getString("cohort"), (Integer) rs.getObject("current_semester"),
                rs.getBigDecimal("current_gpa"), (Integer) rs.getObject("total_credits_earned"),
                rs.getString("academic_status"), rs.getObject("date_of_birth", LocalDate.class)), studentId.toString())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student account not found"));
    }

    List<TranscriptRow> transcript(UUID studentId) {
        return jdbc.query("""
                SELECT r.id, c.code AS course_code, c.name AS course_name, c.credits,
                       sem.code AS semester_code, sem.name AS semester_name,
                       r.final_score, r.status, r.attempt_number, r.credits_earned
                FROM student_course_records r JOIN courses c ON c.id = r.course_id
                JOIN semesters sem ON sem.id = r.semester_id
                WHERE r.student_id = ? ORDER BY sem.start_date DESC, c.code, r.attempt_number DESC
                """, (rs, row) -> new TranscriptRow(rs.getString("id"), rs.getString("course_code"),
                rs.getString("course_name"), rs.getInt("credits"), rs.getString("semester_code"),
                rs.getString("semester_name"), rs.getBigDecimal("final_score"), rs.getString("status"),
                rs.getInt("attempt_number"), rs.getInt("credits_earned")), studentId.toString());
    }

    Progress progress(UUID studentId) {
        Profile p = profile(studentId);
        if (p.curriculumVersionId() == null) {
            return new Progress(0, 0, 0, p.currentGpa(), 0, List.of());
        }
        Integer required = jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.credits), 0) FROM curriculum_courses cc
                JOIN courses c ON c.id = cc.course_id
                WHERE cc.curriculum_version_id = ? AND cc.is_mandatory = TRUE
                """, Integer.class, p.curriculumVersionId());
        Integer passed = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.course_id) FROM student_course_records r
                JOIN curriculum_courses cc ON cc.course_id = r.course_id AND cc.curriculum_version_id = ?
                WHERE r.student_id = ? AND r.status IN ('PASSED', 'EXEMPTED')
                """, Integer.class, p.curriculumVersionId(), studentId.toString());
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ?",
                Integer.class, p.curriculumVersionId());
        List<SemesterSummary> semesters = jdbc.query("""
                SELECT sem.code, sem.name, ss.total_credits_enrolled, ss.total_credits_earned,
                       ss.semester_gpa, ss.cumulative_gpa, ss.academic_standing
                FROM semester_summaries ss JOIN semesters sem ON sem.id = ss.semester_id
                WHERE ss.student_id = ? ORDER BY sem.start_date
                """, (rs, row) -> new SemesterSummary(rs.getString("code"), rs.getString("name"),
                rs.getInt("total_credits_enrolled"), rs.getInt("total_credits_earned"),
                rs.getBigDecimal("semester_gpa"), rs.getBigDecimal("cumulative_gpa"),
                rs.getString("academic_standing")), studentId.toString());
        return new Progress(required == null ? 0 : required,
                p.totalCreditsEarned() == null ? 0 : p.totalCreditsEarned(),
                total == null ? 0 : total, p.currentGpa(), passed == null ? 0 : passed, semesters);
    }

    List<Course> courses(String search, int page, int size) {
        return catalog.courses(search, page, size);
    }

    CoursePage coursesPaged(String search, int page, int size) {
        return catalog.coursesPaged(search, page, size);
    }

    Course course(UUID id) {
        return catalog.course(id);
    }

    List<Prerequisite> prerequisites(UUID courseId) {
        return catalog.prerequisites(courseId);
    }

    Curriculum curriculum(UUID studentId) {
        return catalog.curriculum(profile(studentId).curriculumVersionId());
    }

    List<CurriculumCourse> curriculumCourses(UUID studentId) {
        return catalog.curriculumCourses(curriculum(studentId));
    }

    List<AcademicWarning> warnings(UUID studentId, AcademicWarningScheduler warningScheduler) {
        try {
            if (warningScheduler != null) {
                warningScheduler.syncStudentWarnings(studentId);
            }
        } catch (Exception ex) {
            log.debug("Skipping warning sync for student {}: {}", studentId, ex.getMessage());
        }
        try {
            return jdbc.query("""
                    SELECT w.id, w.student_id, w.semester_id, sem.code AS semester_code, sem.name AS semester_name,
                           w.warning_type, w.severity, w.description, w.action_required,
                           w.acknowledged_at, w.resolved_at, w.created_at
                    FROM academic_warnings w
                    LEFT JOIN semesters sem ON sem.id = w.semester_id
                    WHERE w.student_id = ?
                    ORDER BY w.created_at DESC, w.id DESC
                    """, (rs, row) -> new AcademicWarning(
                    rs.getString("id"), rs.getString("student_id"), rs.getString("semester_id"),
                    rs.getString("semester_code"), rs.getString("semester_name"), rs.getString("warning_type"),
                    rs.getString("severity"), rs.getString("description"), rs.getString("action_required"),
                    rs.getTimestamp("acknowledged_at") != null ? rs.getTimestamp("acknowledged_at").toLocalDateTime() : null,
                    rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toLocalDateTime() : null,
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null),
                    studentId.toString());
        } catch (Exception ex) {
            log.debug("Could not load academic warnings for student {}: {}", studentId, ex.getMessage());
            return List.of();
        }
    }
}
