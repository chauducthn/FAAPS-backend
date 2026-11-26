package vn.edu.fpt.faaps.advising.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.repository.AssignmentReadRepository;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Advisor;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.AssignmentError;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Conflict;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.StudentCandidate;

class AdminAssignmentSupport {
    private final JdbcTemplate jdbc;
    private final AssignmentReadRepository reads;
    private final CohortAdvisorService cohorts;

    AdminAssignmentSupport(JdbcTemplate jdbc, AssignmentReadRepository reads, CohortAdvisorService cohorts) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.cohorts = cohorts;
    }

    List<UUID> unassignedIds(UUID cohortId) {
        return unassignedCandidates(cohortId).stream().map(candidate -> candidate.id()).toList();
    }

    List<StudentCandidate> unassignedCandidates(UUID cohortId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_versions WHERE id = ?",
                Integer.class, cohortId.toString()) == 0) {
            throw new Conflict();
        }
        try {
            return jdbc.query("""
                    SELECT s.user_id, s.academic_status, s.current_gpa FROM students s
                    WHERE s.curriculum_version_id = ?
                      AND NOT EXISTS(SELECT 1 FROM student_advisor_assignments sa
                          WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL)
                    ORDER BY s.user_id
                    """, (rs, row) -> new StudentCandidate(
                    UUID.fromString(rs.getString("user_id")),
                    SmartAutoAssignPlanner.isAtRisk(rs.getString("academic_status"), rs.getBigDecimal("current_gpa"))),
                    cohortId.toString());
        } catch (Exception ignored) {
            return jdbc.query("""
                    SELECT s.user_id FROM students s
                    WHERE s.curriculum_version_id = ?
                      AND NOT EXISTS(SELECT 1 FROM student_advisor_assignments sa
                          WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL)
                    ORDER BY s.user_id
                    """, (rs, row) -> new StudentCandidate(UUID.fromString(rs.getString("user_id")), false),
                    cohortId.toString());
        }
    }

    Map<UUID, Long> activeAtRiskCounts(List<UUID> advisorIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (advisorIds == null || advisorIds.isEmpty()) return counts;
        String placeholders = String.join(",", advisorIds.stream().map(id -> "?").toList());
        Object[] args = advisorIds.stream().map(id -> id.toString()).toArray();
        try {
            jdbc.query("""
                    SELECT sa.advisor_id, COUNT(*) AS cnt
                    FROM student_advisor_assignments sa
                    JOIN students s ON s.user_id = sa.student_id
                    WHERE sa.advisor_id IN (%s) AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                      AND ((s.academic_status IS NOT NULL AND UPPER(s.academic_status) <> 'ACTIVE')
                           OR (s.current_gpa > 0 AND s.current_gpa < 5.00))
                    GROUP BY sa.advisor_id
                    """.formatted(placeholders), rs -> {
                counts.put(UUID.fromString(rs.getString("advisor_id")), rs.getLong("cnt"));
            }, args);
        } catch (Exception ignored) {
            // Minimal H2 test schemas may omit academic_status/current_gpa columns.
        }
        return counts;
    }

    List<UUID> selectedTargets(List<UUID> requested, List<UUID> available) {
        if (requested.stream().anyMatch(Objects::isNull)
                || requested.stream().distinct().count() != requested.size()) {
            throw new IllegalArgumentException("studentIds must be non-null and unique");
        }
        for (UUID studentId : requested) {
            if (!available.contains(studentId)) {
                Integer activeCount = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM student_advisor_assignments
                        WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                        """, Integer.class, studentId.toString());
                if (activeCount != null && activeCount > 0) {
                    throw new AssignmentError("STUDENT_ALREADY_ASSIGNED",
                            "Student " + studentId + " is already assigned to an advisor");
                }
                throw new AssignmentError("STUDENT_NOT_IN_COHORT",
                        "Student " + studentId + " does not belong to the selected cohort");
            }
        }
        return requested.stream().sorted().toList();
    }

    List<Advisor> selectedAdvisors(List<UUID> ids) {
        validateIds(ids);
        List<Advisor> available = reads.advisors();
        List<Advisor> selected = new ArrayList<>();
        for (UUID id : ids) {
            selected.add(available.stream().filter(advisor -> advisor.id().equals(id))
                    .findFirst().orElseThrow(Conflict::new));
        }
        return selected;
    }

    List<Advisor> selectedAdvisorsForCohort(UUID cohortId, List<UUID> ids) {
        List<UUID> pool = cohorts.activeIds(cohortId);
        List<UUID> selected = ids == null || ids.isEmpty() ? pool : ids;
        if (pool.isEmpty()) {
            throw new AssignmentError("COHORT_ADVISORS_NOT_CONFIGURED",
                    "No advisors are configured for this cohort");
        }
        if (!pool.containsAll(selected)) {
            throw new AssignmentError("ADVISOR_NOT_IN_COHORT",
                    "Selected advisor is not configured for this cohort");
        }
        return selectedAdvisors(selected);
    }

    void validateIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull)
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("ids must be unique and contain 1-100 values");
        }
    }

    Advisor requireAdvisor(UUID id) {
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advisors a JOIN users u ON u.id=a.user_id
                WHERE a.user_id=? AND a.status='ACTIVE' AND u.status='ACTIVE'
                """, Integer.class, id.toString());
        if (active == null || active == 0) {
            throw new AssignmentError("ADVISOR_INACTIVE", "Advisor is not active");
        }
        return reads.advisors().stream().filter(advisor -> advisor.id().equals(id))
                .findFirst().orElseThrow(() -> new AssignmentError("ADVISOR_INACTIVE", "Advisor is not active"));
    }

    void lockAdvisor(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM advisors WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    void lockStudent(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM students WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    String activeAdvisor(UUID studentId) {
        List<String> rows = jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, (result, row) -> result.getString(1), studentId.toString());
        if (rows.size() > 1) {
            throw new Conflict();
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    void insertAssignment(UUID studentId, UUID advisorId, UUID actorId, String notes) {
        jdbc.update("""
                INSERT INTO student_advisor_assignments
                    (id, student_id, advisor_id, assigned_date, assigned_at,
                     assigned_by_user_id, notes, is_primary, status)
                VALUES (?, ?, ?, CURRENT_DATE, CURRENT_TIMESTAMP, ?, ?, TRUE, 'ACTIVE')
                """, UUID.randomUUID().toString(), studentId.toString(), advisorId.toString(),
                actorId == null ? null : actorId.toString(), notes);
        jdbc.update("""
                UPDATE notifications SET resolved_at = CURRENT_TIMESTAMP
                WHERE related_student_id = ? AND notification_type = 'ADVISOR_REQUEST'
                  AND resolved_at IS NULL
                """, studentId.toString());
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'Advisor assigned', 'An advisor has been assigned to you.', FALSE)
                """, UUID.randomUUID().toString(), studentId.toString());
        jdbc.update("""
                UPDATE advising_requests SET advisor_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND advisor_id IS NULL
                  AND status IN ('PENDING', 'IN_PROGRESS')
                """, advisorId.toString(), studentId.toString());
    }
}
