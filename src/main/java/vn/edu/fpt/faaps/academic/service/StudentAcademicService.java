package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudentAcademicService {
    private final JdbcTemplate jdbc;

    public StudentAcademicService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile profile(UUID studentId) {
        return jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.phone, u.date_of_birth, u.status AS account_status,
                       s.student_code, s.major_id, m.code AS major_code, m.name AS major_name,
                       s.curriculum_version_id, cv.version AS cohort, s.current_semester,
                       s.current_gpa, s.total_credits_earned, s.academic_status
                FROM users u LEFT JOIN students s ON s.user_id=u.id
                LEFT JOIN majors m ON m.id=s.major_id
                LEFT JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id
                WHERE u.id=?
                """, (rs, row) -> new Profile(rs.getString("id"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("account_status"),
                rs.getString("student_code"), rs.getString("major_id"), rs.getString("major_code"),
                rs.getString("major_name"), rs.getString("curriculum_version_id"),
                rs.getString("cohort"), (Integer) rs.getObject("current_semester"),
                rs.getBigDecimal("current_gpa"), (Integer) rs.getObject("total_credits_earned"),
                rs.getString("academic_status"), rs.getObject("date_of_birth", LocalDate.class)), studentId.toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student account not found"));
    }

    @Transactional
    public Profile updateProfile(UUID studentId, String fullName, String phone) {
        if (fullName.isBlank()) throw new IllegalArgumentException("fullName is required");
        jdbc.update("UPDATE users SET full_name=?, phone=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                fullName.strip(), phone == null || phone.isBlank() ? null : phone.strip(), studentId.toString());
        return profile(studentId);
    }

    public List<TranscriptRow> transcript(UUID studentId) {
        return jdbc.query("""
                SELECT r.id, c.code AS course_code, c.name AS course_name, c.credits,
                       sem.code AS semester_code, sem.name AS semester_name,
                       r.final_score, r.status, r.attempt_number, r.credits_earned
                FROM student_course_records r JOIN courses c ON c.id=r.course_id
                JOIN semesters sem ON sem.id=r.semester_id
                WHERE r.student_id=? ORDER BY sem.start_date DESC, c.code, r.attempt_number DESC
                """, (rs, row) -> new TranscriptRow(rs.getString("id"), rs.getString("course_code"),
                rs.getString("course_name"), rs.getInt("credits"), rs.getString("semester_code"),
                rs.getString("semester_name"), rs.getBigDecimal("final_score"), rs.getString("status"),
                rs.getInt("attempt_number"), rs.getInt("credits_earned")), studentId.toString());
    }

    public Progress progress(UUID studentId) {
        Profile p = profile(studentId);
        if (p.curriculumVersionId() == null) return new Progress(0, 0, 0, p.currentGpa(), 0, List.of());
        Integer required = jdbc.queryForObject("""
                SELECT COALESCE(SUM(c.credits),0) FROM curriculum_courses cc
                JOIN courses c ON c.id=cc.course_id WHERE cc.curriculum_version_id=? AND cc.is_mandatory=TRUE
                """, Integer.class, p.curriculumVersionId());
        Integer passed = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.course_id) FROM student_course_records r
                JOIN curriculum_courses cc ON cc.course_id=r.course_id AND cc.curriculum_version_id=?
                WHERE r.student_id=? AND r.status IN ('PASSED','EXEMPTED')
                """, Integer.class, p.curriculumVersionId(), studentId.toString());
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id=?",
                Integer.class, p.curriculumVersionId());
        List<SemesterSummary> semesters = jdbc.query("""
                SELECT sem.code, sem.name, ss.total_credits_enrolled, ss.total_credits_earned,
                       ss.semester_gpa, ss.cumulative_gpa, ss.academic_standing
                FROM semester_summaries ss JOIN semesters sem ON sem.id=ss.semester_id
                WHERE ss.student_id=? ORDER BY sem.start_date
                """, (rs, row) -> new SemesterSummary(rs.getString("code"), rs.getString("name"),
                rs.getInt("total_credits_enrolled"), rs.getInt("total_credits_earned"),
                rs.getBigDecimal("semester_gpa"), rs.getBigDecimal("cumulative_gpa"),
                rs.getString("academic_standing")), studentId.toString());
        return new Progress(required == null ? 0 : required, p.totalCreditsEarned() == null ? 0 : p.totalCreditsEarned(),
                total == null ? 0 : total, p.currentGpa(), passed == null ? 0 : passed, semesters);
    }

    public List<Course> courses(String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        String term = "%" + (search == null ? "" : search.strip().toLowerCase()) + "%";
        return jdbc.query("""
                SELECT c.id,c.code,c.name,c.credits,c.description,c.objectives,c.learning_outcomes,
                       cat.name AS category_name FROM courses c JOIN course_categories cat ON cat.id=c.category_id
                WHERE c.status='ACTIVE' AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)
                ORDER BY c.code LIMIT ? OFFSET ?
                """, (rs, row) -> mapCourse(rs), term, term, size, page * size);
    }

    public Course course(UUID id) {
        return jdbc.query("""
                SELECT c.id,c.code,c.name,c.credits,c.description,c.objectives,c.learning_outcomes,
                       cat.name AS category_name FROM courses c JOIN course_categories cat ON cat.id=c.category_id
                WHERE c.id=? AND c.status='ACTIVE'
                """, (rs, row) -> mapCourse(rs), id.toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found"));
    }

    public List<Prerequisite> prerequisites(UUID courseId) {
        course(courseId);
        return jdbc.query("""
                SELECT p.id, c.code, c.name, p.group_no, p.type, p.minimum_score
                FROM course_prerequisites p JOIN courses c ON c.id=p.prerequisite_course_id
                WHERE p.course_id=? ORDER BY p.group_no,c.code
                """, (rs, row) -> new Prerequisite(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("group_no"), rs.getString("type"),
                rs.getBigDecimal("minimum_score")), courseId.toString());
    }

    public Curriculum curriculum(UUID studentId) {
        Profile p = profile(studentId);
        if (p.curriculumVersionId() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Academic profile not assigned");
        return jdbc.query("""
                SELECT cv.id,cv.version,cv.name,cv.description,m.code AS major_code,m.name AS major_name
                FROM curriculum_versions cv JOIN majors m ON m.id=cv.major_id WHERE cv.id=?
                """, (rs, row) -> new Curriculum(rs.getString("id"), rs.getString("version"),
                rs.getString("name"), rs.getString("description"), rs.getString("major_code"),
                rs.getString("major_name")), p.curriculumVersionId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Curriculum not found"));
    }

    public List<CurriculumCourse> curriculumCourses(UUID studentId) {
        Curriculum c = curriculum(studentId);
        return jdbc.query("""
                SELECT cc.course_id,co.code,co.name,co.credits,cc.semester_number,cc.is_mandatory,cc.group_name
                FROM curriculum_courses cc JOIN courses co ON co.id=cc.course_id
                WHERE cc.curriculum_version_id=? ORDER BY cc.semester_number,co.code
                """, (rs, row) -> new CurriculumCourse(rs.getString("course_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("credits"), rs.getInt("semester_number"),
                rs.getBoolean("is_mandatory"), rs.getString("group_name")), c.id());
    }

    private Course mapCourse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Course(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("credits"), rs.getString("category_name"), rs.getString("description"),
                rs.getString("objectives"), rs.getString("learning_outcomes"));
    }

    public record Profile(String id, String fullName, String email, String phone, String accountStatus,
            String studentCode, String majorId, String majorCode, String majorName,
            String curriculumVersionId, String cohort, Integer currentSemester, BigDecimal currentGpa,
            Integer totalCreditsEarned, String academicStatus, LocalDate dateOfBirth) {}
    public record TranscriptRow(String id, String courseCode, String courseName, int credits,
            String semesterCode, String semesterName, BigDecimal finalScore, String status,
            int attemptNumber, int creditsEarned) {}
    public record SemesterSummary(String code, String name, int creditsEnrolled, int creditsEarned,
            BigDecimal semesterGpa, BigDecimal cumulativeGpa, String standing) {}
    public record Progress(int requiredCredits, int earnedCredits, int curriculumCourses,
            BigDecimal currentGpa, int completedCourses, List<SemesterSummary> semesters) {}
    public record Course(String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes) {}
    public record Prerequisite(String id, String courseCode, String courseName, int groupNo,
            String type, BigDecimal minimumScore) {}
    public record Curriculum(String id, String version, String name, String description,
            String majorCode, String majorName) {}
    public record CurriculumCourse(String courseId, String code, String name, int credits,
            int semesterNumber, boolean mandatory, String groupName) {}
}

