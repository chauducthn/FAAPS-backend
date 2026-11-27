package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists per-student Study Journey goals (graduation term, career orientation, target GPA). */
@Service
public class StudentStudyJourneyService {
    private static final Set<String> ORIENTATIONS = Set.of("JOB", "ACADEMIC", "RESEARCH", "ENTREPRENEURIAL");
    private final JdbcTemplate jdbc;

    public StudentStudyJourneyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTable();
    }

    private void ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS student_study_journey_goals ("
                    + "student_id CHAR(36) NOT NULL PRIMARY KEY, "
                    + "expected_graduation_term VARCHAR(32) NULL, "
                    + "expected_graduation_semester_id CHAR(36) NULL, "
                    + "career_orientation VARCHAR(32) NOT NULL DEFAULT 'JOB', "
                    + "target_job_role VARCHAR(160) NULL, "
                    + "target_gpa DECIMAL(4,2) NULL, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (RuntimeException ignored) { /* minimal test schema */ }
    }

    public StudyJourneyGoal get(UUID studentId) {
        try {
            return jdbc.query("""
                    SELECT expected_graduation_term, expected_graduation_semester_id,
                           career_orientation, target_job_role, target_gpa
                    FROM student_study_journey_goals WHERE student_id = ?
                    """, rs -> rs.next()
                    ? new StudyJourneyGoal(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getBigDecimal(5) == null ? null : rs.getBigDecimal(5).doubleValue())
                    : StudyJourneyGoal.empty(),
                    studentId.toString());
        } catch (RuntimeException ignored) {
            return StudyJourneyGoal.empty();
        }
    }

    @Transactional
    public StudyJourneyGoal save(UUID studentId, StudyJourneyGoal input) {
        String term = clean(input == null ? null : input.expectedGraduationTerm(), 32);
        String semId = clean(input == null ? null : input.expectedGraduationSemesterId(), 36);
        String orient = normalizeOrientation(input == null ? null : input.careerOrientation());
        String role = clean(input == null ? null : input.targetJobRole(), 160);
        BigDecimal gpa = normalizeGpa(input == null ? null : input.targetGpa());

        int updated = jdbc.update("""
                UPDATE student_study_journey_goals
                SET expected_graduation_term = ?, expected_graduation_semester_id = ?,
                    career_orientation = ?, target_job_role = ?, target_gpa = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ?
                """, term, semId, orient, role, gpa, studentId.toString());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO student_study_journey_goals
                        (student_id, expected_graduation_term, expected_graduation_semester_id,
                         career_orientation, target_job_role, target_gpa)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, studentId.toString(), term, semId, orient, role, gpa);
        }
        return get(studentId);
    }

    private static String clean(String v, int max) {
        if (v == null || v.isBlank()) return null;
        String s = v.strip();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String normalizeOrientation(String v) {
        if (v == null || v.isBlank()) return "JOB";
        String u = v.strip().toUpperCase(Locale.ROOT);
        return ORIENTATIONS.contains(u) ? u : "JOB";
    }

    private static BigDecimal normalizeGpa(Double v) {
        if (v == null || !Double.isFinite(v)) return null;
        double clamped = Math.max(0.0, Math.min(10.0, v));
        return BigDecimal.valueOf(Math.round(clamped * 100.0) / 100.0);
    }

    public record StudyJourneyGoal(
            String expectedGraduationTerm,
            String expectedGraduationSemesterId,
            String careerOrientation,
            String targetJobRole,
            Double targetGpa) {
        public static StudyJourneyGoal empty() {
            return new StudyJourneyGoal(null, null, "JOB", null, null);
        }
    }
}
