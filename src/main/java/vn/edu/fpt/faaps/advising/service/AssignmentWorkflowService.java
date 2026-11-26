package vn.edu.fpt.faaps.advising.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentWorkflowService {
    private final JdbcTemplate jdbc;
    private final AdminAssignmentService assignments;
    private final CohortAdvisorService cohorts;
    private final AssignmentWorkflowQueryHelper queries;
    private final AssignedStudentsQueryHelper assignedQueries;

    public AssignmentWorkflowService(JdbcTemplate jdbc, AdminAssignmentService assignments,
            CohortAdvisorService cohorts) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.cohorts = cohorts;
        this.queries = new AssignmentWorkflowQueryHelper(jdbc);
        this.assignedQueries = new AssignedStudentsQueryHelper(jdbc);
    }

    public Page<Map<String, Object>> assigned(UUID advisorId, UUID cohortId, String majorCode,
            String search, int page, int size) {
        return assignedQueries.assigned(advisorId, cohortId, majorCode, search, page, size);
    }

    public List<Map<String, Object>> unassigned(UUID cohortId) {
        return unassigned(cohortId, null, null, 0, 100).items();
    }

    public Page<Map<String, Object>> unassigned(UUID cohortId, String search, Boolean hasPendingRequest,
            int page, int size) {
        return queries.unassigned(cohortId, search, hasPendingRequest, page, size);
    }

    public Map<String, Object> summary() {
        return queries.summary();
    }

    public List<Map<String, Object>> advisorsWithCohorts() {
        return advisorsWithCohorts(null, null, 0, 100).items();
    }

    public Page<Map<String, Object>> advisorsWithCohorts(UUID cohortId, String search, int page, int size) {
        return queries.advisorsWithCohorts(cohortId, search, page, size);
    }

    @Transactional
    public Map<String, Object> updateLimit(UUID advisorId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("maxAdvisees must be positive");
        }
        int updated = jdbc.update(
                "UPDATE advisors SET max_advisees = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                limit, advisorId.toString());
        if (updated == 0) {
            throw new AdminAssignmentService.Conflict();
        }
        long load = assignments.advisor(advisorId).advisor().activeStudents();
        return Map.of("advisorId", advisorId, "maxAdvisees", limit, "activeStudents", load,
                "overLimitBy", Math.max(0, load - limit));
    }

    @Transactional
    public Map<String, Object> assignCohort(UUID cohortId, UUID advisorId) {
        return assignCohort(cohortId, advisorId, null, null);
    }

    @Transactional
    public Map<String, Object> assignCohort(UUID cohortId, UUID advisorId, UUID actorId, String notes) {
        cohorts.lockCohort(cohortId);
        cohorts.include(cohortId, advisorId);
        assignments.lockAdvisor(advisorId);
        List<UUID> ids = unassigned(cohortId).stream()
                .map(item -> UUID.fromString((String) item.get("id")))
                .toList();
        int count = 0;
        for (UUID studentId : ids.stream().sorted().toList()) {
            assignments.lockStudent(studentId);
            if (assignments.activeAdvisor(studentId) == null) {
                assignments.insertAssignment(studentId, advisorId, actorId, notes);
                count++;
            }
        }
        AdminAssignmentService.Advisor advisor = assignments.advisor(advisorId).advisor();
        int excess = advisor.maxAdvisees() == null ? 0
                : (int) Math.max(0, advisor.activeStudents() - advisor.maxAdvisees());
        return Map.of("assigned", count, "advisorId", advisorId, "overLimitBy", excess,
                "warning", excess > 0);
    }

    public Map<String, Object> previewCohortAssignment(UUID cohortId, UUID advisorId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_versions WHERE id = ?", Integer.class,
                cohortId.toString()) == 0) {
            throw new AdminAssignmentService.Conflict();
        }
        AdminAssignmentService.Advisor advisor = assignments.advisor(advisorId).advisor();
        int count = unassigned(cohortId).size();
        long after = advisor.activeStudents() + count;
        int excess = advisor.maxAdvisees() == null ? 0
                : (int) Math.max(0, after - advisor.maxAdvisees());
        return Map.of("cohortId", cohortId, "advisorId", advisorId, "unassignedStudents", count,
                "totalAfter", after, "overLimitBy", excess, "warning", excess > 0);
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {
        public long totalPages() {
            return (totalElements + size - 1) / size;
        }
    }
}
