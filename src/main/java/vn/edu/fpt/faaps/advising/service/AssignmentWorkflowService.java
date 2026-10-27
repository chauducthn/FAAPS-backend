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

    public AssignmentWorkflowService(JdbcTemplate jdbc, AdminAssignmentService assignments,
            CohortAdvisorService cohorts) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.cohorts = cohorts;
    }

    public List<Map<String, Object>> unassigned(UUID cohortId) {
        return unassigned(cohortId, null, null, 0, 100).items();
    }

    public Page<Map<String, Object>> unassigned(UUID cohortId, String search, Boolean hasPendingRequest,
            int page, int size) {
        validatePage(page, size);
        String filter = cohortId == null ? "" : " AND cv.id = ?";
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String requestFilter = hasPendingRequest == null ? "" : " AND EXISTS(SELECT 1 FROM notifications nr WHERE nr.related_student_id=s.user_id AND nr.notification_type='ADVISOR_REQUEST' AND nr.resolved_at IS NULL) = ?";
        String searchFilter = term == null ? "" : " AND (LOWER(s.student_code) LIKE ? OR LOWER(u.full_name) LIKE ? OR LOWER(m.code) LIKE ? OR LOWER(cv.version) LIKE ?)";
        List<Object> params = new java.util.ArrayList<>();
        if (cohortId != null) params.add(cohortId.toString());
        if (hasPendingRequest != null) params.add(hasPendingRequest);
        if (term != null) for (int i = 0; i < 4; i++) params.add(term);
        String base = """
                SELECT s.user_id, s.student_code, u.full_name, cv.id AS cohort_id,
                       cv.version AS cohort, m.code AS major_code,
                       EXISTS(SELECT 1 FROM notifications n
                              WHERE n.related_student_id = s.user_id
                                AND n.notification_type = 'ADVISOR_REQUEST'
                                AND n.resolved_at IS NULL) AS has_pending_request
                FROM students s JOIN users u ON u.id = s.user_id
                JOIN roles r ON r.id=u.role_id AND r.code='STUDENT'
                JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                JOIN majors m ON m.id = cv.major_id
                WHERE NOT EXISTS(SELECT 1 FROM student_advisor_assignments sa
                                 WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE'
                                   AND sa.end_date IS NULL)
                """ + filter + requestFilter + searchFilter;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") filtered", Long.class, params.toArray());
        params.add(size);
        params.add(page * size);
        List<Map<String, Object>> items = jdbc.query(base + " ORDER BY cv.version, s.student_code LIMIT ? OFFSET ?",
                (result, row) -> Map.<String, Object>of(
                        "id", result.getString("user_id"), "studentCode", result.getString("student_code"),
                        "fullName", result.getString("full_name"), "cohortId", result.getString("cohort_id"),
                        "cohort", result.getString("cohort"), "majorCode", result.getString("major_code"),
                        "hasPendingRequest", result.getBoolean("has_pending_request")), params.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    public Map<String, Object> summary() {
        List<Map<String, Object>> cohorts = jdbc.query("""
                SELECT cv.id AS cohort_id, cv.version AS cohort, m.code AS major_code,
                       COUNT(s.user_id) AS total,
                       SUM(CASE WHEN EXISTS(SELECT 1 FROM student_advisor_assignments sa
                           WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE'
                             AND sa.end_date IS NULL) THEN 1 ELSE 0 END) AS assigned
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                LEFT JOIN students s ON s.curriculum_version_id = cv.id
                GROUP BY cv.id, cv.version, m.code ORDER BY m.code, cv.version
                """, (result, row) -> Map.<String, Object>of(
                        "cohortId", result.getString("cohort_id"), "cohort", result.getString("cohort"),
                        "majorCode", result.getString("major_code"), "total", result.getLong("total"),
                        "assigned", result.getLong("assigned"),
                        "unassigned", result.getLong("total") - result.getLong("assigned")));
        long profiled = cohorts.stream().mapToLong(item -> (long) item.get("total")).sum();
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id
                WHERE r.code = 'STUDENT'
                """, Long.class);
        long assigned = cohorts.stream().mapToLong(item -> (long) item.get("assigned")).sum();
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT n.related_student_id) FROM notifications n
                WHERE n.notification_type = 'ADVISOR_REQUEST' AND n.resolved_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM student_advisor_assignments sa
                      WHERE sa.student_id = n.related_student_id AND sa.status = 'ACTIVE'
                        AND sa.end_date IS NULL)
                """, Long.class);
        return Map.of("total", total, "assigned", assigned, "unassigned", profiled - assigned,
                "missingAcademicProfile", total - profiled,
                "pendingAdvisorRequests", pending, "cohorts", cohorts);
    }

    public List<Map<String, Object>> advisorsWithCohorts() {
        return advisorsWithCohorts(null, null, 0, 100).items();
    }

    public Page<Map<String, Object>> advisorsWithCohorts(UUID cohortId, String search, int page, int size) {
        validatePage(page, size);
        String base = """
                SELECT a.user_id, u.email, u.full_name, a.max_advisees,
                       a.status,
                       cv.id AS cohort_id, cv.version AS cohort, m.code AS major_code,
                       ca.cohort_id IS NOT NULL AS in_pool,
                       COUNT(sa.id) AS student_count
                FROM advisors a JOIN users u ON u.id = a.user_id
                JOIN roles r ON r.id=u.role_id AND r.code='ADVISOR'
                LEFT JOIN (
                    SELECT advisor_id, cohort_id FROM cohort_advisors WHERE is_active = TRUE
                    UNION
                    SELECT sa.advisor_id, s.curriculum_version_id
                    FROM student_advisor_assignments sa JOIN students s ON s.user_id = sa.student_id
                    WHERE sa.status = 'ACTIVE' AND sa.end_date IS NULL
                ) ac ON ac.advisor_id = a.user_id
                LEFT JOIN curriculum_versions cv ON cv.id = ac.cohort_id
                LEFT JOIN majors m ON m.id = cv.major_id
                LEFT JOIN cohort_advisors ca ON ca.advisor_id = a.user_id
                    AND ca.cohort_id = ac.cohort_id AND ca.is_active = TRUE
                LEFT JOIN students s ON s.curriculum_version_id = ac.cohort_id
                LEFT JOIN student_advisor_assignments sa ON sa.student_id = s.user_id
                    AND sa.advisor_id = a.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                  AND (? IS NULL OR cv.id = ?)
                  AND (? IS NULL OR LOWER(u.email) LIKE ? OR LOWER(u.full_name) LIKE ? OR LOWER(m.code) LIKE ?)
                GROUP BY a.user_id, u.email, u.full_name, a.max_advisees,
                         a.status, cv.id, cv.version, m.code, ca.cohort_id
                """;
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        Object[] baseParams = {cohortId == null ? null : cohortId.toString(), cohortId == null ? null : cohortId.toString(), term, term, term, term};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") result_rows", Long.class, baseParams);
        List<Object> params = new java.util.ArrayList<>(java.util.Arrays.asList(baseParams));
        params.add(size);
        params.add(page * size);
        List<Map<String, Object>> items = jdbc.query(base + " ORDER BY full_name, cohort "
                + "LIMIT ? OFFSET ?", (result, row) -> {
                    java.util.HashMap<String, Object> item = new java.util.HashMap<>();
                    item.put("advisorId", result.getString("user_id"));
                    item.put("email", result.getString("email"));
                    item.put("fullName", result.getString("full_name"));
                    item.put("maxAdvisees", result.getObject("max_advisees"));
                    item.put("status", result.getString("status"));
                    item.put("cohortId", result.getString("cohort_id"));
                    item.put("cohort", result.getString("cohort"));
                    item.put("majorCode", result.getString("major_code"));
                    item.put("inPool", result.getBoolean("in_pool"));
                    item.put("studentCount", result.getLong("student_count"));
                    return item;
                }, params.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("page or size is invalid");
    }

    @Transactional
    public Map<String, Object> updateLimit(UUID advisorId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("maxAdvisees must be positive");
        if (jdbc.update("UPDATE advisors SET max_advisees = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                limit, advisorId.toString()) == 0) throw new AdminAssignmentService.Conflict();
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
        List<UUID> ids = unassigned(cohortId).stream().map(item -> UUID.fromString((String) item.get("id"))).toList();
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
                cohortId.toString()) == 0) throw new AdminAssignmentService.Conflict();
        AdminAssignmentService.Advisor advisor = assignments.advisor(advisorId).advisor();
        int count = unassigned(cohortId).size();
        long after = advisor.activeStudents() + count;
        int excess = advisor.maxAdvisees() == null ? 0
                : (int) Math.max(0, after - advisor.maxAdvisees());
        return Map.of("cohortId", cohortId, "advisorId", advisorId, "unassignedStudents", count,
                "totalAfter", after, "overLimitBy", excess, "warning", excess > 0);
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {
        public long totalPages() { return (totalElements + size - 1) / size; }
    }

}

