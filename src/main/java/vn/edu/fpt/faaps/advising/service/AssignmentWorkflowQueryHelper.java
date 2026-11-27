package vn.edu.fpt.faaps.advising.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.service.AssignmentWorkflowService.Page;

class AssignmentWorkflowQueryHelper {
    private final JdbcTemplate jdbc;
    private final boolean hasAvatarColumn;

    AssignmentWorkflowQueryHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.hasAvatarColumn = detectAvatarColumn(jdbc);
    }

    private static boolean detectAvatarColumn(JdbcTemplate jdbc) {
        try {
            jdbc.query("SELECT avatar_url FROM users WHERE 1 = 0", (rs, row) -> null);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String avatarExpr() {
        return hasAvatarColumn ? "u.avatar_url" : "NULL AS avatar_url";
    }

    Page<Map<String, Object>> unassigned(UUID cohortId, String cohort, String search, Boolean hasPendingRequest,
            int page, int size) {
        validatePage(page, size);
        String cohortCode = cohort == null || cohort.isBlank() ? null : cohort.strip().toUpperCase();
        String filter = (cohortId == null ? "" : " AND cv.id = ?")
                + (cohortCode == null ? "" : " AND UPPER(cv.version) = ?");
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String requestFilter = hasPendingRequest == null ? ""
                : " AND EXISTS(SELECT 1 FROM notifications nr WHERE nr.related_student_id=s.user_id"
                + " AND nr.notification_type='ADVISOR_REQUEST' AND nr.resolved_at IS NULL) = ?";
        String searchFilter = term == null ? ""
                : " AND (LOWER(s.student_code) LIKE ? OR LOWER(u.full_name) LIKE ?"
                + " OR LOWER(m.code) LIKE ? OR LOWER(cv.version) LIKE ?)";
        List<Object> params = new ArrayList<>();
        if (cohortId != null) {
            params.add(cohortId.toString());
        }
        if (cohortCode != null) {
            params.add(cohortCode);
        }
        if (hasPendingRequest != null) {
            params.add(hasPendingRequest);
        }
        if (term != null) {
            for (int i = 0; i < 4; i++) {
                params.add(term);
            }
        }
        String base = "SELECT s.user_id, s.student_code, u.full_name, " + avatarExpr() + ", cv.id AS cohort_id,"
                + " cv.version AS cohort, m.code AS major_code,"
                + " EXISTS(SELECT 1 FROM notifications n WHERE n.related_student_id = s.user_id"
                + " AND n.notification_type = 'ADVISOR_REQUEST' AND n.resolved_at IS NULL) AS has_pending_request"
                + " FROM students s JOIN users u ON u.id = s.user_id"
                + " JOIN roles r ON r.id=u.role_id AND r.code='STUDENT'"
                + " JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id"
                + " JOIN majors m ON m.id = cv.major_id"
                + " WHERE NOT EXISTS(SELECT 1 FROM student_advisor_assignments sa"
                + " WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL)"
                + filter + requestFilter + searchFilter;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") filtered", Long.class, params.toArray());
        params.add(size);
        params.add(page * size);
        List<Map<String, Object>> items = jdbc.query(base + " ORDER BY cv.version, s.student_code LIMIT ? OFFSET ?",
                (result, row) -> {
                    HashMap<String, Object> item = new HashMap<>();
                    item.put("id", result.getString("user_id"));
                    item.put("studentCode", result.getString("student_code"));
                    item.put("fullName", result.getString("full_name"));
                    item.put("avatarUrl", result.getString("avatar_url"));
                    item.put("cohortId", result.getString("cohort_id"));
                    item.put("cohort", result.getString("cohort"));
                    item.put("majorCode", result.getString("major_code"));
                    item.put("hasPendingRequest", result.getBoolean("has_pending_request"));
                    return item;
                }, params.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    Map<String, Object> summary() {
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

    Page<Map<String, Object>> advisorsWithCohorts(UUID cohortId, String search, int page, int size) {
        validatePage(page, size);
        String avatarCol = hasAvatarColumn ? "u.avatar_url" : "NULL AS avatar_url";
        String avatarGroup = hasAvatarColumn ? ", u.avatar_url" : "";
        String base = "SELECT a.user_id, u.email, u.full_name, " + avatarCol + ", a.max_advisees, a.status,"
                + " cv.id AS cohort_id, cv.version AS cohort, m.code AS major_code,"
                + " ca.cohort_id IS NOT NULL AS in_pool, COUNT(sa.id) AS student_count"
                + " FROM advisors a JOIN users u ON u.id = a.user_id"
                + " JOIN roles r ON r.id=u.role_id AND r.code='ADVISOR'"
                + " LEFT JOIN (SELECT advisor_id, cohort_id FROM cohort_advisors WHERE is_active = TRUE"
                + " UNION SELECT sa.advisor_id, s.curriculum_version_id FROM student_advisor_assignments sa"
                + " JOIN students s ON s.user_id = sa.student_id WHERE sa.status = 'ACTIVE' AND sa.end_date IS NULL"
                + " ) ac ON ac.advisor_id = a.user_id"
                + " LEFT JOIN curriculum_versions cv ON cv.id = ac.cohort_id"
                + " LEFT JOIN majors m ON m.id = cv.major_id"
                + " LEFT JOIN cohort_advisors ca ON ca.advisor_id = a.user_id"
                + " AND ca.cohort_id = ac.cohort_id AND ca.is_active = TRUE"
                + " LEFT JOIN students s ON s.curriculum_version_id = ac.cohort_id"
                + " LEFT JOIN student_advisor_assignments sa ON sa.student_id = s.user_id"
                + " AND sa.advisor_id = a.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL"
                + " WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'"
                + " AND (? IS NULL OR cv.id = ?)"
                + " AND (? IS NULL OR LOWER(u.email) LIKE ? OR LOWER(u.full_name) LIKE ? OR LOWER(m.code) LIKE ?)"
                + " GROUP BY a.user_id, u.email, u.full_name" + avatarGroup
                + ", a.max_advisees, a.status, cv.id, cv.version, m.code, ca.cohort_id";
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String cohortStr = cohortId == null ? null : cohortId.toString();
        Object[] baseParams = {cohortStr, cohortStr, term, term, term, term};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base + ") result_rows", Long.class, baseParams);
        List<Object> params = new ArrayList<>(Arrays.asList(baseParams));
        params.add(size);
        params.add(page * size);
        List<Map<String, Object>> items = jdbc.query(base + " ORDER BY full_name, cohort LIMIT ? OFFSET ?",
                (result, row) -> {
                    HashMap<String, Object> item = new HashMap<>();
                    item.put("advisorId", result.getString("user_id"));
                    item.put("email", result.getString("email"));
                    item.put("fullName", result.getString("full_name"));
                    item.put("avatarUrl", result.getString("avatar_url"));
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
        if (page < 0 || size < 1 || size > 10000 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page or size is invalid");
        }
    }
}
