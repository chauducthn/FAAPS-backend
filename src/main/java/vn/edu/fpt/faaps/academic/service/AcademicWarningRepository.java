package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

final class AcademicWarningRepository {
    private static final Logger log = LoggerFactory.getLogger(AcademicWarningRepository.class);

    private final JdbcTemplate jdbc;

    AcademicWarningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> findActiveStudentIds() {
        return jdbc.query(
                "SELECT user_id FROM students WHERE academic_status IN ('ACTIVE', 'PROBATION', 'SUSPENDED')",
                (rs, row) -> rs.getString("user_id"));
    }

    StudentSnapshot findStudentSnapshot(String studentId) {
        List<StudentSnapshot> snapshots = jdbc.query("""
                SELECT current_gpa, academic_status
                FROM students WHERE user_id = ?
                """, (rs, row) -> new StudentSnapshot(
                rs.getBigDecimal("current_gpa"),
                rs.getString("academic_status")), studentId);
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    boolean ensureWarning(String studentId, String semesterId, String warningType,
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
        } catch (Exception ex) {
            log.debug("Skipping warning notification insert for student {}: {}", studentId, ex.getMessage());
        }
        return true;
    }

    int countGradedCourses(String studentId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM student_course_records
                    WHERE student_id = ? AND (final_score IS NOT NULL OR status IN ('PASSED', 'FAILED'))
                    """, Integer.class, studentId);
            return count == null ? 0 : count;
        } catch (Exception ex) {
            log.debug("Could not count graded courses for student {}: {}", studentId, ex.getMessage());
            return 0;
        }
    }

    int countUnrecoveredFailedCourses(String studentId) {
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
        } catch (Exception ex) {
            log.debug("Could not count failed courses for student {}: {}", studentId, ex.getMessage());
            return 0;
        }
    }

    AttendanceSnap worstAttendanceRisk(String studentId) {
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
                    rs.getString("result")), studentId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            log.debug("Could not query attendance risk for student {}: {}", studentId, ex.getMessage());
            return null;
        }
    }

    String resolveCurrentSemesterId() {
        try {
            List<String> current = jdbc.query(
                    "SELECT id FROM semesters WHERE is_current = TRUE ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!current.isEmpty()) {
                return current.get(0);
            }
        } catch (Exception ex) {
            log.debug("Could not resolve current semester: {}", ex.getMessage());
        }
        try {
            List<String> any = jdbc.query("SELECT id FROM semesters LIMIT 1", (rs, row) -> rs.getString(1));
            return any.isEmpty() ? null : any.get(0);
        } catch (Exception ex) {
            log.debug("Could not resolve fallback semester: {}", ex.getMessage());
            return null;
        }
    }

    String resolveSystemIssuerId(String fallbackUserId) {
        try {
            List<String> admin = jdbc.query("SELECT user_id FROM admin_account WHERE singleton_id = 1",
                    (rs, row) -> rs.getString(1));
            if (!admin.isEmpty()) {
                return admin.get(0);
            }
        } catch (Exception ex) {
            log.debug("Could not resolve admin issuer, using fallback: {}", ex.getMessage());
        }
        return fallbackUserId;
    }

    record StudentSnapshot(BigDecimal currentGpa, String academicStatus) {
    }

    record AttendanceSnap(int absentSessions, int totalSessions, String absentRate, String result) {
    }
}
