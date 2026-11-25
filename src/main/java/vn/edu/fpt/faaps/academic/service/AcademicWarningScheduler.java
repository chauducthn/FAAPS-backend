package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicWarningScheduler {
    private static final BigDecimal GPA_WARNING_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal GPA_PROBATION_THRESHOLD = new BigDecimal("4.00");

    private final JdbcTemplate jdbc;

    public AcademicWarningScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Automatic daily cron job (02:00 AM by default) to evaluate all active students
     * and issue academic warnings for low GPA (< 5.00), unrecovered failed courses, or high absence.
     */
    @Scheduled(cron = "${faaps.academic.warning-cron:0 0 2 * * *}")
    public void runDailyWarningCheck() {
        try {
            syncAllStudentWarnings();
        } catch (Exception ignored) {
            // Non-blocking when DB is not yet migrated or during test startup without tables.
        }
    }

    @Transactional
    public int syncAllStudentWarnings() {
        List<String> studentIds = jdbc.query(
                "SELECT user_id FROM students WHERE academic_status IN ('ACTIVE', 'PROBATION', 'SUSPENDED')",
                (rs, row) -> rs.getString("user_id"));
        int generated = 0;
        for (String id : studentIds) {
            try {
                generated += syncStudentWarnings(UUID.fromString(id));
            } catch (Exception ignored) {
            }
        }
        return generated;
    }

    @Transactional
    public int syncStudentWarnings(UUID studentId) {
        String semesterId = resolveCurrentSemesterId();
        String issuerId = resolveSystemIssuerId(studentId.toString());
        if (semesterId == null || issuerId == null) {
            return 0;
        }

        List<StudentSnapshot> snapshots = jdbc.query("""
                SELECT current_gpa, academic_status
                FROM students WHERE user_id = ?
                """, (rs, row) -> new StudentSnapshot(
                rs.getBigDecimal("current_gpa"),
                rs.getString("academic_status")
        ), studentId.toString());
        if (snapshots.isEmpty()) {
            return 0;
        }

        StudentSnapshot snap = snapshots.get(0);
        int created = 0;

        // 1. Check LOW_GPA (< 5.00)
        BigDecimal gpa = snap.currentGpa();
        boolean hasGraded = countGradedCourses(studentId.toString()) > 0;
        boolean lowGpa = (gpa != null && gpa.compareTo(BigDecimal.ZERO) > 0 && gpa.compareTo(GPA_WARNING_THRESHOLD) < 0)
                || (gpa != null && gpa.compareTo(BigDecimal.ZERO) == 0 && hasGraded);
        if (lowGpa) {
            String severity = gpa.compareTo(GPA_PROBATION_THRESHOLD) < 0 ? "PROBATION" : "WARNING";
            String desc = String.format(Locale.ROOT,
                    "Cumulative GPA is %.2f (below minimum threshold of 5.00).", gpa.doubleValue());
            String action = "Meet with your academic advisor to adjust your study plan and retake low-grade courses.";
            if (ensureWarning(studentId.toString(), semesterId, "LOW_GPA", severity, desc, action, issuerId)) {
                created++;
            }
        }

        // 2. Check FAILED_COURSES
        int failedCount = countUnrecoveredFailedCourses(studentId.toString());
        if (failedCount > 0) {
            String severity = failedCount >= 3 ? "FINAL_WARNING" : (failedCount == 2 ? "PROBATION" : "WARNING");
            String desc = "You have " + failedCount + " unrecovered failed course(s) on your transcript.";
            String action = "Prioritize registering for retake classes in the upcoming semester.";
            if (ensureWarning(studentId.toString(), semesterId, "FAILED_COURSES", severity, desc, action, issuerId)) {
                created++;
            }
        }

        // 3. Check ATTENDANCE
        AttendanceSnap att = worstAttendanceRisk(studentId.toString());
        if (att != null) {
            String severity = "FAIL".equalsIgnoreCase(att.result()) ? "PROBATION" : "WARNING";
            String desc = String.format(Locale.ROOT,
                    "High absence recorded: %d/%d total sessions (%s%% absent, status: %s).",
                    att.absentSessions(), att.totalSessions(), att.absentRate(), att.result());
            String action = "Attend all remaining class sessions to avoid failing the attendance condition.";
            if (ensureWarning(studentId.toString(), semesterId, "ATTENDANCE", severity, desc, action, issuerId)) {
                created++;
            }
        }

        return created;
    }

    private boolean ensureWarning(String studentId, String semesterId, String warningType,
            String severity, String description, String actionRequired, String issuerId) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM academic_warnings
                WHERE student_id = ? AND semester_id = ? AND warning_type = ? AND resolved_at IS NULL
                """, Integer.class, studentId, semesterId, warningType);
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO academic_warnings
                (id, student_id, semester_id, warning_type, severity, description, action_required,
                 issued_by_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), studentId, semesterId, warningType, severity,
                description, actionRequired, issuerId);
        try {
            jdbc.update("""
                    INSERT INTO notifications
                    (id, recipient_user_id, notification_type, title, message, related_student_id, is_read, created_at)
                    VALUES (?, ?, 'WARNING', ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), studentId,
                    "Academic Warning (" + warningType.replace('_', ' ') + ")",
                    description, studentId);
        } catch (Exception ignored) {
        }
        return true;
    }

    private int countGradedCourses(String studentId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM student_course_records
                    WHERE student_id = ? AND (final_score IS NOT NULL OR status IN ('PASSED', 'FAILED'))
                    """, Integer.class, studentId);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int countUnrecoveredFailedCourses(String studentId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT r.course_id)
                    FROM student_course_records r
                    WHERE r.student_id = ? AND r.status = 'FAILED'
                      AND NOT EXISTS (
                          SELECT 1 FROM student_course_records r2
                          WHERE r2.student_id = r.student_id
                            AND r2.course_id = r.course_id
                            AND r2.status IN ('PASSED', 'EXEMPTED')
                      )
                    """, Integer.class, studentId);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private AttendanceSnap worstAttendanceRisk(String studentId) {
        try {
            List<AttendanceSnap> list = jdbc.query("""
                    SELECT absent_sessions, total_sessions, absent_rate, result
                    FROM student_course_attendance
                    WHERE student_id = ? AND result IN ('WARNING', 'FAIL')
                    ORDER BY absent_rate DESC LIMIT 1
                    """, (rs, r) -> new AttendanceSnap(
                    rs.getInt("absent_sessions"),
                    rs.getInt("total_sessions"),
                    rs.getBigDecimal("absent_rate") != null ? rs.getBigDecimal("absent_rate").toPlainString() : "0",
                    rs.getString("result")
            ), studentId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveCurrentSemesterId() {
        try {
            List<String> current = jdbc.query(
                    "SELECT id FROM semesters WHERE is_current = TRUE ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!current.isEmpty()) return current.get(0);
        } catch (Exception ignored) {
        }
        try {
            List<String> any = jdbc.query("SELECT id FROM semesters LIMIT 1", (rs, row) -> rs.getString(1));
            return any.isEmpty() ? null : any.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveSystemIssuerId(String fallbackUserId) {
        try {
            List<String> admin = jdbc.query("SELECT user_id FROM admin_account WHERE singleton_id = 1",
                    (rs, row) -> rs.getString(1));
            if (!admin.isEmpty()) return admin.get(0);
        } catch (Exception ignored) {
        }
        return fallbackUserId;
    }

    private record StudentSnapshot(BigDecimal currentGpa, String academicStatus) {}
    private record AttendanceSnap(int absentSessions, int totalSessions, String absentRate, String result) {}
}
