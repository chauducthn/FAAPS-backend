package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;

final class TranscriptScoreRecalculator {
    private TranscriptScoreRecalculator() {}

    static void applyRowsAndRecalculate(JdbcTemplate jdbc, UUID studentId, List<ImportRow> rows, boolean replaceAll) {
        if (replaceAll) {
            jdbc.update("DELETE FROM student_course_records WHERE student_id=?", studentId.toString());
            jdbc.update("DELETE FROM semester_summaries WHERE student_id=?", studentId.toString());
        }
        upsertCourseRecords(jdbc, studentId, rows);
        recalculateStudentTotals(jdbc, studentId);
        recalculateSemesterSummaries(jdbc, studentId);
    }

    private static void upsertCourseRecords(JdbcTemplate jdbc, UUID studentId, List<ImportRow> rows) {
        for (ImportRow row : rows) {
            Integer credits = jdbc.queryForObject(
                    "SELECT credits FROM courses WHERE id=?", Integer.class, row.courseId());
            int earned = Set.of("PASSED", "EXEMPTED").contains(row.status()) ? credits : 0;
            List<String> existing = jdbc.query("""
                    SELECT id FROM student_course_records
                    WHERE student_id=? AND course_id=? AND semester_id=? AND attempt_number=?
                    FOR UPDATE
                    """, (rs, index) -> rs.getString(1),
                    studentId.toString(), row.courseId(), row.semesterId(), row.attemptNumber());
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO student_course_records
                        (id,student_id,course_id,semester_id,final_score,status,attempt_number,credits_earned)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID().toString(), studentId.toString(), row.courseId(), row.semesterId(),
                        row.finalScore(), row.status(), row.attemptNumber(), earned);
            } else {
                jdbc.update(
                        "UPDATE student_course_records SET final_score=?,status=?,credits_earned=?,"
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        row.finalScore(), row.status(), earned, existing.get(0));
            }
        }
    }

    private static void recalculateStudentTotals(JdbcTemplate jdbc, UUID studentId) {
        jdbc.update("""
                UPDATE students s SET total_credits_earned=(
                  SELECT COALESCE(SUM(earned),0) FROM (
                    SELECT MAX(credits_earned) earned FROM student_course_records
                    WHERE student_id=? GROUP BY course_id
                  ) distinct_courses), updated_at=CURRENT_TIMESTAMP WHERE s.user_id=?
                """, studentId.toString(), studentId.toString());
        jdbc.update("""
                UPDATE students s SET current_gpa=(
                  SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                  FROM student_course_records r JOIN courses c ON c.id=r.course_id
                  WHERE r.student_id=? AND r.final_score IS NOT NULL AND r.status IN ('PASSED','FAILED')
                ), updated_at=CURRENT_TIMESTAMP WHERE s.user_id=?
                """, studentId.toString(), studentId.toString());
    }

    private static void recalculateSemesterSummaries(JdbcTemplate jdbc, UUID studentId) {
        List<String> affectedSemesters = jdbc.query(
                "SELECT DISTINCT semester_id FROM student_course_records WHERE student_id=?",
                (rs, index) -> rs.getString(1), studentId.toString());
        for (String semesterId : affectedSemesters) {
            List<String> summaries = jdbc.query(
                    "SELECT id FROM semester_summaries WHERE student_id=? AND semester_id=?",
                    (rs, index) -> rs.getString(1), studentId.toString(), semesterId);
            if (summaries.isEmpty()) {
                jdbc.update("INSERT INTO semester_summaries(id,student_id,semester_id) VALUES (?,?,?)",
                        UUID.randomUUID().toString(), studentId.toString(), semesterId);
            }
            jdbc.update("""
                    UPDATE semester_summaries SET
                      total_credits_enrolled=(SELECT COALESCE(SUM(c.credits),0) FROM student_course_records r
                        JOIN courses c ON c.id=r.course_id WHERE r.student_id=? AND r.semester_id=?),
                      total_credits_earned=(SELECT COALESCE(SUM(r.credits_earned),0) FROM student_course_records r
                        WHERE r.student_id=? AND r.semester_id=?),
                      semester_gpa=(SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                        FROM student_course_records r JOIN courses c ON c.id=r.course_id
                        WHERE r.student_id=? AND r.semester_id=? AND r.final_score IS NOT NULL
                          AND r.status IN ('PASSED','FAILED')),
                      cumulative_gpa=(SELECT COALESCE(ROUND(SUM(r.final_score*c.credits)/NULLIF(SUM(c.credits),0),2),0)
                        FROM student_course_records r JOIN courses c ON c.id=r.course_id
                        JOIN semesters completed ON completed.id=r.semester_id
                        JOIN semesters target ON target.id=?
                        WHERE r.student_id=? AND completed.start_date<=target.start_date
                          AND r.final_score IS NOT NULL AND r.status IN ('PASSED','FAILED')),
                      updated_at=CURRENT_TIMESTAMP WHERE student_id=? AND semester_id=?
                    """, studentId.toString(), semesterId, studentId.toString(), semesterId,
                    studentId.toString(), semesterId, semesterId, studentId.toString(),
                    studentId.toString(), semesterId);
        }
    }
}
