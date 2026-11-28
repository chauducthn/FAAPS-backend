package vn.edu.fpt.faaps.advising.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Flow 3 (passive advising): the evidence behind a warning, so the advisor can see why a student needs advising
 * before scheduling a meeting or writing an email.
 */
@Service
public class AdvisorOutreachService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorOutreachService.class);
    /** Scores under this are shown as "low" even when the course was passed. */
    private static final BigDecimal LOW_SCORE = new BigDecimal("5.50");

    private final JdbcTemplate jdbc;

    public AdvisorOutreachService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Semesters that have attendance or grade data, newest first, plus the current one. */
    public List<SemesterOption> semesters() {
        try {
            return semestersWithCounts();
        } catch (RuntimeException ex) {
            // Attendance tables are created on first import; before that list the semesters without counts.
            return jdbc.query("""
                    SELECT id, code, name, start_date, end_date, is_current FROM semesters
                    WHERE start_date <= ? ORDER BY start_date DESC
                    """, (rs, row) -> new SemesterOption(rs.getString("id"), rs.getString("code"),
                    rs.getString("name"), String.valueOf(rs.getObject("start_date")),
                    String.valueOf(rs.getObject("end_date")), rs.getBoolean("is_current")
                    || isToday(rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                    0, 0), LocalDate.now().plusMonths(1));
        }
    }

    private List<SemesterOption> semestersWithCounts() {
        return jdbc.query("""
                SELECT sem.id, sem.code, sem.name, sem.start_date, sem.end_date, sem.is_current,
                       (SELECT COUNT(*) FROM student_course_attendance a WHERE a.semester_id = sem.id) AS attendance_rows,
                       (SELECT COUNT(*) FROM student_course_records r WHERE r.semester_id = sem.id) AS grade_rows
                FROM semesters sem
                WHERE sem.start_date <= ?
                ORDER BY sem.start_date DESC
                """, (rs, row) -> new SemesterOption(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                String.valueOf(rs.getObject("start_date")), String.valueOf(rs.getObject("end_date")),
                rs.getBoolean("is_current") || isToday(rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)),
                rs.getInt("attendance_rows"), rs.getInt("grade_rows")), LocalDate.now().plusMonths(1));
    }

    public StudentSignals signals(UUID advisorId, UUID studentId, UUID semesterId) {
        requireAdvisee(advisorId, studentId);
        String student = studentId.toString();
        String semester = semesterId == null ? null : semesterId.toString();

        List<AttendanceSignal> attendance = safe(() -> jdbc.query("""
                SELECT c.code, c.name, sem.code AS semester_code, a.absent_sessions, a.total_sessions,
                       a.absent_rate, a.result
                FROM student_course_attendance a
                JOIN courses c ON c.id = a.course_id
                LEFT JOIN semesters sem ON sem.id = a.semester_id
                WHERE a.student_id = ? AND (? IS NULL OR a.semester_id = ?)
                ORDER BY sem.start_date DESC, a.absent_rate DESC
                """, (rs, row) -> new AttendanceSignal(rs.getString("code"), rs.getString("name"),
                rs.getString("semester_code"), rs.getInt("absent_sessions"), rs.getInt("total_sessions"),
                rs.getBigDecimal("absent_rate") == null ? 0.0 : rs.getBigDecimal("absent_rate").doubleValue(),
                rs.getString("result")), student, semester, semester));

        List<GradeSignal> grades = safe(() -> jdbc.query("""
                SELECT c.code, c.name, sem.code AS semester_code, r.final_score, r.status, r.attempt_number
                FROM student_course_records r
                JOIN courses c ON c.id = r.course_id
                LEFT JOIN semesters sem ON sem.id = r.semester_id
                WHERE r.student_id = ? AND (? IS NULL OR r.semester_id = ?)
                  AND (r.status = 'FAILED' OR (r.final_score IS NOT NULL AND r.final_score < ?))
                ORDER BY sem.start_date DESC, r.final_score ASC
                """, (rs, row) -> new GradeSignal(rs.getString("code"), rs.getString("name"),
                rs.getString("semester_code"),
                rs.getBigDecimal("final_score") == null ? null : rs.getBigDecimal("final_score").doubleValue(),
                rs.getString("status"), rs.getInt("attempt_number")), student, semester, semester, LOW_SCORE));

        List<ContactSignal> contacts = safe(() -> jdbc.query("""
                SELECT s.id, s.session_type, s.status, s.scheduled_at, s.agenda, s.summary, u.full_name AS advisor_name
                FROM advising_sessions s
                LEFT JOIN users u ON u.id = s.advisor_id
                WHERE s.student_id = ?
                ORDER BY COALESCE(s.scheduled_at, s.created_at) DESC
                LIMIT 10
                """, (rs, row) -> new ContactSignal(rs.getString("id"), rs.getString("session_type"),
                rs.getString("status"), iso(rs.getTimestamp("scheduled_at")), rs.getString("agenda"),
                rs.getString("summary"), rs.getString("advisor_name")), student));

        List<SemesterGpa> gpaHistory = safe(() -> jdbc.query("""
                SELECT sem.code, ss.semester_gpa, ss.cumulative_gpa
                FROM semester_summaries ss JOIN semesters sem ON sem.id = ss.semester_id
                WHERE ss.student_id = ? ORDER BY sem.start_date
                """, (rs, row) -> new SemesterGpa(rs.getString("code"),
                rs.getBigDecimal("semester_gpa") == null ? null : rs.getBigDecimal("semester_gpa").doubleValue(),
                rs.getBigDecimal("cumulative_gpa") == null ? null : rs.getBigDecimal("cumulative_gpa").doubleValue()),
                student));

        List<StudentContact> profile = jdbc.query("""
                SELECT u.full_name, u.email, s.student_code, s.current_gpa, s.academic_status
                FROM users u JOIN students s ON s.user_id = u.id WHERE u.id = ?
                """, (rs, row) -> new StudentContact(rs.getString("full_name"), rs.getString("email"),
                rs.getString("student_code"),
                rs.getBigDecimal("current_gpa") == null ? null : rs.getBigDecimal("current_gpa").doubleValue(),
                rs.getString("academic_status")), student);
        if (profile.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        }
        return new StudentSignals(student, profile.get(0), attendance, grades, gpaHistory, contacts);
    }

    private void requireAdvisee(UUID advisorId, UUID studentId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE advisor_id = ? AND student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, Integer.class, advisorId.toString(), studentId.toString());
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        }
    }

    private <T> List<T> safe(java.util.function.Supplier<List<T>> query) {
        try {
            return query.get();
        } catch (RuntimeException ex) {
            log.debug("Outreach signal query skipped: {}", ex.getMessage());
            return List.of();
        }
    }

    private static boolean isToday(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        return start != null && end != null && !today.isBefore(start) && !today.isAfter(end);
    }

    private static String iso(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toString();
    }

    public record SemesterOption(String id, String code, String name, String startDate, String endDate,
            boolean current, int attendanceRows, int gradeRows) {}

    public record AttendanceSignal(String courseCode, String courseName, String semesterCode, int absentSessions,
            int totalSessions, double absentRate, String result) {}

    public record GradeSignal(String courseCode, String courseName, String semesterCode, Double finalScore,
            String status, int attemptNumber) {}

    public record ContactSignal(String id, String sessionType, String status, String scheduledAt, String agenda,
            String summary, String advisorName) {}

    public record SemesterGpa(String semesterCode, Double semesterGpa, Double cumulativeGpa) {}

    public record StudentContact(String fullName, String email, String studentCode, Double currentGpa,
            String academicStatus) {}

    public record StudentSignals(String studentId, StudentContact student, List<AttendanceSignal> attendance,
            List<GradeSignal> lowGrades, List<SemesterGpa> gpaHistory, List<ContactSignal> contacts) {}
}
