package vn.edu.fpt.faaps.advising.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CohortAdvisorService {
    private final JdbcTemplate jdbc;

    public CohortAdvisorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CohortAdvisor> list(UUID cohortId) {
        requireCohort(cohortId);
        return jdbc.query("""
                SELECT ca.advisor_id, u.email, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS cohort_students
                FROM cohort_advisors ca JOIN advisors a ON a.user_id = ca.advisor_id
                JOIN users u ON u.id = a.user_id
                LEFT JOIN student_advisor_assignments sa ON sa.advisor_id = ca.advisor_id
                  AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                  AND EXISTS (SELECT 1 FROM students s WHERE s.user_id = sa.student_id
                              AND s.curriculum_version_id = ca.cohort_id)
                WHERE ca.cohort_id = ? AND ca.is_active = TRUE
                GROUP BY ca.advisor_id, u.email, u.full_name, a.max_advisees
                ORDER BY u.full_name, ca.advisor_id
                """, (result, row) -> new CohortAdvisor(
                        UUID.fromString(result.getString("advisor_id")),
                        result.getString("email"), result.getString("full_name"),
                        (Integer) result.getObject("max_advisees"), result.getLong("cohort_students")),
                cohortId.toString());
    }

    @Transactional
    public List<CohortAdvisor> replace(UUID cohortId, List<UUID> advisorIds) {
        if (advisorIds == null || advisorIds.size() > 100
                || advisorIds.stream().distinct().count() != advisorIds.size()) {
            throw new IllegalArgumentException("advisorIds must be unique and contain at most 100 values");
        }
        lockCohort(cohortId);
        for (UUID advisorId : advisorIds) requireActiveAdvisor(advisorId);
        jdbc.update("UPDATE cohort_advisors SET is_active = FALSE WHERE cohort_id = ?", cohortId.toString());
        for (UUID advisorId : advisorIds) include(cohortId, advisorId);
        return list(cohortId);
    }

    @Transactional
    public List<UUID> replaceForAdvisor(UUID advisorId, List<UUID> cohortIds) {
        if (cohortIds == null || cohortIds.size() > 1) {
            throw new IllegalArgumentException("An advisor can only be assigned to 1 major and 1 cohort");
        }
        requireActiveAdvisor(advisorId);
        UUID targetCohort = cohortIds.isEmpty() ? null : cohortIds.get(0);
        jdbc.update("UPDATE cohort_advisors SET is_active = FALSE WHERE advisor_id = ?", advisorId.toString());
        if (targetCohort != null) include(targetCohort, advisorId);
        unassignStudentsOutsideCohort(advisorId, targetCohort);
        return cohortIds;
    }

    @Transactional
    public int assignCohortsBulk(List<UUID> advisorIds, List<UUID> cohortIds) {
        if (advisorIds == null || advisorIds.isEmpty() || advisorIds.size() > 100
                || advisorIds.stream().distinct().count() != advisorIds.size()) {
            throw new IllegalArgumentException("advisorIds must contain 1 to 100 unique values");
        }
        if (cohortIds == null || cohortIds.size() != 1) {
            throw new IllegalArgumentException("Each advisor can only be assigned to 1 major and 1 cohort");
        }
        UUID targetCohort = cohortIds.get(0);
        for (UUID advisorId : advisorIds) {
            requireActiveAdvisor(advisorId);
            jdbc.update("UPDATE cohort_advisors SET is_active = FALSE WHERE advisor_id = ?", advisorId.toString());
            include(targetCohort, advisorId);
            unassignStudentsOutsideCohort(advisorId, targetCohort);
        }
        return advisorIds.size();
    }

    @Transactional
    public void include(UUID cohortId, UUID advisorId) {
        lockCohort(cohortId);
        requireActiveAdvisor(advisorId);
        jdbc.update("""
                UPDATE cohort_advisors SET is_active = FALSE
                WHERE advisor_id = ? AND cohort_id <> ?
                """, advisorId.toString(), cohortId.toString());
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cohort_advisors WHERE cohort_id = ? AND advisor_id = ?
                """, Integer.class, cohortId.toString(), advisorId.toString());
        if (existing == null || existing == 0) {
            jdbc.update("""
                    INSERT INTO cohort_advisors (cohort_id, advisor_id, is_active)
                    VALUES (?, ?, TRUE)
                    """, cohortId.toString(), advisorId.toString());
        } else {
            jdbc.update("""
                    UPDATE cohort_advisors SET is_active = TRUE
                    WHERE cohort_id = ? AND advisor_id = ?
                    """, cohortId.toString(), advisorId.toString());
        }
    }

    private void unassignStudentsOutsideCohort(UUID advisorId, UUID targetCohortId) {
        if (targetCohortId == null) {
            jdbc.update("""
                    UPDATE student_advisor_assignments
                    SET status = 'COMPLETED', end_date = CURRENT_DATE,
                        updated_at = CURRENT_TIMESTAMP, notes = 'Advisor cohort cleared'
                    WHERE advisor_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                    """, advisorId.toString());
            return;
        }
        jdbc.update("""
                UPDATE student_advisor_assignments
                SET status = 'COMPLETED', end_date = CURRENT_DATE,
                    updated_at = CURRENT_TIMESTAMP, notes = 'Advisor reassigned to another cohort'
                WHERE advisor_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                  AND student_id IN (
                      SELECT user_id FROM students WHERE curriculum_version_id <> ?
                  )
                """, advisorId.toString(), targetCohortId.toString());
    }

    public List<UUID> activeIds(UUID cohortId) {
        requireCohort(cohortId);
        return jdbc.query("""
                SELECT advisor_id FROM cohort_advisors
                WHERE cohort_id = ? AND is_active = TRUE ORDER BY advisor_id
                """, (result, row) -> UUID.fromString(result.getString(1)), cohortId.toString());
    }

    private void requireCohort(UUID cohortId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_versions WHERE id = ?",
                Integer.class, cohortId.toString());
        if (count == null || count == 0) throw new AdminAssignmentService.NotFound("Cohort not found");
    }

    void lockCohort(UUID cohortId) {
        if (jdbc.query("SELECT id FROM curriculum_versions WHERE id = ? FOR UPDATE",
                (result, row) -> result.getString(1), cohortId.toString()).isEmpty()) {
            throw new AdminAssignmentService.Conflict();
        }
    }

    private void requireActiveAdvisor(UUID advisorId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advisors a JOIN users u ON u.id = a.user_id
                WHERE a.user_id = ? AND a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                """, Integer.class, advisorId.toString());
        if (count == null || count == 0) throw new AdminAssignmentService.AssignmentError("ADVISOR_INACTIVE", "Advisor is not active");
    }

    public record CohortAdvisor(UUID advisorId, String email, String fullName,
            Integer maxAdvisees, long cohortStudents) {}
}

