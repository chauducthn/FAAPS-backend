package vn.edu.fpt.faaps.infrastructure.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LocalSeedAdvisingSql {

    private final JdbcTemplate jdbc;

    LocalSeedAdvisingSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureAttendanceConfig() {
        if (LocalDatabaseSeeder.safeCount(jdbc, "attendance_default_config") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_default_config WHERE singleton_id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO attendance_default_config
                        (singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions)
                    VALUES (1, 20, 80, 3)
                    """);
        }
    }

    void ensureStudentAttendance(String studentId, String courseId, String semesterId,
            int totalSessions, int absentSessions, BigDecimal absentRate, String result) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "student_course_attendance") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_course_attendance
                WHERE student_id = ? AND course_id = ? AND semester_id = ?
                """, Integer.class, studentId, courseId, semesterId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_course_attendance (
                        id, student_id, course_id, semester_id, total_sessions, absent_sessions, absent_rate, result
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, courseId, semesterId,
                    totalSessions, absentSessions, absentRate, result);
        }
    }

    void ensureAcademicWarning(String studentId, String semesterId, String warningType,
            String severity, String description, String actionRequired, String issuedByUserId) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "academic_warnings") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM academic_warnings
                WHERE student_id = ? AND semester_id = ? AND warning_type = ?
                """, Integer.class, studentId, semesterId, warningType);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO academic_warnings (
                        id, student_id, semester_id, warning_type, severity, description,
                        action_required, issued_by_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, semesterId, warningType,
                    severity, description, actionRequired, issuedByUserId);
        }
    }

    String ensureStudyPlan(String studentId, String semesterId, String planType, int totalCredits, String status) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "study_plans") < 0) {
            return null;
        }
        List<String> existing = jdbc.query(
                "SELECT id FROM study_plans WHERE student_id = ? AND semester_id = ? AND version = 1",
                (rs, i) -> rs.getString("id"), studentId, semesterId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_plans
                    (id, student_id, semester_id, version, is_current, plan_type, total_credits_planned, status)
                VALUES (?, ?, ?, 1, TRUE, ?, ?, ?)
                """, id, studentId, semesterId, planType, totalCredits, status);
        return id;
    }

    void ensureStudyPlanCourse(String planId, String courseId, int plannedCredits) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "study_plan_courses") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_courses WHERE study_plan_id = ? AND course_id = ?",
                Integer.class, planId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority)
                    VALUES (?, ?, ?, ?, 'HIGH')
                    """, UUID.randomUUID().toString(), planId, courseId, plannedCredits);
        }
    }

    void ensureAdvisingRequest(String studentId, String advisorId, String requestType,
            String title, String description, String status) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "advising_requests") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM advising_requests WHERE student_id = ? AND title = ?",
                Integer.class, studentId, title);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO advising_requests
                        (id, student_id, advisor_id, request_type, title, description, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, advisorId, requestType, title, description, status);
        }
    }

    void ensureAdvisingSession(String studentId, String advisorId, String sessionType,
            String location, String agenda, String summary, String status) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "advising_sessions") < 0) {
            return;
        }
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM advising_sessions WHERE student_id = ? AND agenda = ?",
                    Integer.class, studentId, agenda);
            if (count != null && count == 0) {
                jdbc.update("""
                        INSERT INTO advising_sessions
                            (id, student_id, advisor_id, session_type, scheduled_at, location, agenda, summary, status)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), studentId, advisorId,
                        sessionType, location, agenda, summary, status);
            }
        } catch (Exception ex) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM advising_sessions WHERE student_id = ? AND advisor_id = ?",
                    Integer.class, studentId, advisorId);
            if (count != null && count == 0) {
                jdbc.update("""
                        INSERT INTO advising_sessions (id, student_id, advisor_id, scheduled_at, status)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                        """, UUID.randomUUID().toString(), studentId, advisorId, status);
            }
        }
    }
}
