package vn.edu.fpt.faaps.infrastructure.config;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LocalSeedPeopleSql {

    private final JdbcTemplate jdbc;

    LocalSeedPeopleSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureAdvisor(String userId, String advisorCode, String specialization) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "advisors") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM advisors WHERE user_id = ?", Integer.class, userId);
        if (count != null && count == 0) {
            try {
                jdbc.update("""
                        INSERT INTO advisors (user_id, advisor_code, specialization, max_advisees, status)
                        VALUES (?, ?, ?, 30, 'ACTIVE')
                        """, userId, advisorCode, specialization);
            } catch (Exception ex) {
                jdbc.update("INSERT INTO advisors (user_id, max_advisees, status) VALUES (?, 30, 'ACTIVE')", userId);
            }
        }
    }

    void ensureStudent(String userId, String studentCode, String majorId, String curriculumVersionId,
            String enrollmentSemId, String expectedGradSemId, int currentSem,
            BigDecimal currentGpa, int totalCreditsEarned, String academicStatus) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "students") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM students WHERE user_id = ? OR student_code = ?",
                Integer.class, userId, studentCode);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO students (
                        user_id, student_code, major_id, curriculum_version_id,
                        enrollment_semester_id, expected_graduation_semester_id,
                        current_semester, current_gpa, total_credits_earned, academic_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, studentCode, majorId, curriculumVersionId,
                    enrollmentSemId, expectedGradSemId, currentSem, currentGpa, totalCreditsEarned, academicStatus);
        }
    }

    void ensureAdvisorAssignment(String studentId, String advisorId, String groupName) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "student_advisor_assignments") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_advisor_assignments WHERE student_id = ? AND status = 'ACTIVE'",
                Integer.class, studentId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_advisor_assignments (
                        id, student_id, advisor_id, group_name, assigned_date, is_primary, status
                    ) VALUES (?, ?, ?, ?, CURRENT_DATE, TRUE, 'ACTIVE')
                    """, UUID.randomUUID().toString(), studentId, advisorId, groupName);
        }
    }

    void ensureCourseRecord(String studentId, String courseId, String semesterId,
            BigDecimal finalScore, String status, int creditsEarned) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "student_course_records") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_course_records WHERE student_id = ? AND course_id = ?",
                Integer.class, studentId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_course_records (
                        id, student_id, course_id, semester_id, final_score, status, attempt_number, credits_earned
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    """, UUID.randomUUID().toString(), studentId, courseId, semesterId, finalScore, status, creditsEarned);
        }
    }

    void ensureSemesterSummary(String studentId, String semesterId, int enrolled, int earned,
            BigDecimal semGpa, BigDecimal cumGpa, String standing) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "semester_summaries") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM semester_summaries WHERE student_id = ? AND semester_id = ?",
                Integer.class, studentId, semesterId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO semester_summaries (
                        id, student_id, semester_id, total_credits_enrolled, total_credits_earned,
                        semester_gpa, cumulative_gpa, academic_standing
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, semesterId, enrolled, earned, semGpa, cumGpa, standing);
        }
    }
}
