package vn.edu.fpt.faaps.advising.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class AdvisorAtRiskQueryHelper {
    private static final Logger log = LoggerFactory.getLogger(AdvisorAtRiskQueryHelper.class);
    private final JdbcTemplate jdbc;

    AdvisorAtRiskQueryHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AdviseeRow> loadAdvisees(UUID advisorId, UUID optionalStudentId) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.user_id, s.student_code, u.full_name, m.code AS major_code, m.name AS major_name,
                       s.current_semester, s.current_gpa,
                       COALESCE(s.total_credits_earned, 0) AS total_credits_earned,
                       s.academic_status, s.updated_at
                FROM student_advisor_assignments saa
                JOIN students s ON s.user_id = saa.student_id
                JOIN users u ON u.id = s.user_id
                LEFT JOIN majors m ON m.id = s.major_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                """);
        List<Object> params = new ArrayList<>(List.of(advisorId.toString()));
        if (optionalStudentId != null) {
            sql.append(" AND s.user_id = ?");
            params.add(optionalStudentId.toString());
        }
        sql.append(" ORDER BY u.full_name ASC, s.student_code ASC");

        return jdbc.query(sql.toString(), (rs, row) -> new AdviseeRow(
                rs.getString("user_id"),
                rs.getString("student_code"),
                rs.getString("full_name"),
                rs.getString("major_code"),
                rs.getString("major_name"),
                (Integer) rs.getObject("current_semester"),
                rs.getBigDecimal("current_gpa"),
                rs.getInt("total_credits_earned"),
                rs.getString("academic_status"),
                rs.getTimestamp("updated_at")
        ), params.toArray());
    }

    void acknowledgeWarnings(UUID studentId, String note) {
        try {
            jdbc.update("""
                    UPDATE academic_warnings
                    SET acknowledged_at = CURRENT_TIMESTAMP,
                        action_required = COALESCE(?, action_required),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE student_id = ? AND acknowledged_at IS NULL
                    """, note != null && !note.isBlank() ? note.strip() : null, studentId.toString());
        } catch (Exception ex) {
            log.debug("Could not update academic_warnings for student {}: {}", studentId, ex.getMessage());
        }
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

    AttendanceRiskSummary checkAttendanceRisk(String studentId) {
        try {
            List<String[]> rows = jdbc.query("""
                    SELECT absent_sessions, total_sessions, absent_rate, result
                    FROM student_course_attendance
                    WHERE student_id = ? AND result IN ('WARNING', 'FAIL')
                    ORDER BY absent_rate DESC
                    """, (rs, r) -> new String[] {
                    String.valueOf(rs.getInt("absent_sessions")),
                    String.valueOf(rs.getInt("total_sessions")),
                    rs.getBigDecimal("absent_rate") != null ? rs.getBigDecimal("absent_rate").toPlainString() : "0",
                    rs.getString("result")
            }, studentId);
            if (!rows.isEmpty()) {
                boolean hasFail = rows.stream().anyMatch(r -> "FAIL".equalsIgnoreCase(r[3]));
                String[] worst = rows.get(0);
                String detail = "Absent " + worst[0] + "/" + worst[1] + " sessions (" + worst[2] + "%)";
                return new AttendanceRiskSummary(true, hasFail, detail);
            }
        } catch (Exception ex) {
            log.debug("Could not query student_course_attendance for student {}: {}", studentId, ex.getMessage());
        }
        if (hasUnresolvedWarning(studentId, "ATTENDANCE")) {
            return new AttendanceRiskSummary(true, false, "High absence warning");
        }
        return new AttendanceRiskSummary(false, false, null);
    }

    boolean hasUnresolvedWarning(String studentId, String warningType) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM academic_warnings
                    WHERE student_id = ? AND warning_type = ?
                      AND resolved_at IS NULL AND acknowledged_at IS NULL
                    """, Integer.class, studentId, warningType);
            return count != null && count > 0;
        } catch (Exception ex) {
            log.debug("Could not check {} warning for student {}: {}", warningType, studentId, ex.getMessage());
            return false;
        }
    }

    record AdviseeRow(String id, String studentCode, String fullName, String majorCode,
            String majorName, Integer currentSemester, BigDecimal currentGpa, int totalCreditsEarned,
            String academicStatus, Timestamp updatedAt) {}

    record AttendanceRiskSummary(boolean atRisk, boolean hasFail, String detail) {}
}
