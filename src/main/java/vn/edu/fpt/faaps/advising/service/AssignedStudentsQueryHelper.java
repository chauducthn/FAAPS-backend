package vn.edu.fpt.faaps.advising.service;

import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.service.AssignmentWorkflowService.Page;

class AssignedStudentsQueryHelper {
    private final JdbcTemplate jdbc;
    private final boolean hasAvatarColumn;

    AssignedStudentsQueryHelper(JdbcTemplate jdbc) {
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

    Page<Map<String, Object>> assigned(UUID advisorId, UUID cohortId, String majorCode,
            String search, int page, int size) {
        if (page < 0 || size < 1 || size > 10000 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String major = majorCode == null || majorCode.isBlank() ? null : majorCode.strip().toUpperCase();
        StringBuilder filters = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (advisorId != null) {
            filters.append(" AND sa.advisor_id = ?");
            params.add(advisorId.toString());
        }
        if (cohortId != null) {
            filters.append(" AND cv.id = ?");
            params.add(cohortId.toString());
        }
        if (major != null) {
            filters.append(" AND UPPER(m.code) = ?");
            params.add(major);
        }
        if (term != null) {
            filters.append(" AND (LOWER(s.student_code) LIKE ? OR LOWER(u.full_name) LIKE ?"
                    + " OR LOWER(au.full_name) LIKE ? OR LOWER(m.code) LIKE ? OR LOWER(cv.version) LIKE ?)");
            for (int i = 0; i < 5; i++) {
                params.add(term);
            }
        }
        String avatarExpr = hasAvatarColumn ? "u.avatar_url" : "NULL AS avatar_url";
        String base = "SELECT s.user_id, s.student_code, u.full_name, " + avatarExpr
                + ", cv.id AS cohort_id, cv.version AS cohort, m.code AS major_code,"
                + " sa.advisor_id, au.full_name AS advisor_name, sa.assigned_date"
                + " FROM student_advisor_assignments sa"
                + " JOIN students s ON s.user_id = sa.student_id"
                + " JOIN users u ON u.id = s.user_id"
                + " JOIN roles r ON r.id = u.role_id AND r.code = 'STUDENT'"
                + " JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id"
                + " JOIN majors m ON m.id = cv.major_id"
                + " JOIN users au ON au.id = sa.advisor_id"
                + " WHERE sa.status = 'ACTIVE' AND sa.end_date IS NULL" + filters;
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + base + ") filtered", Long.class, params.toArray());
        params.add(size);
        params.add(page * size);
        List<Map<String, Object>> items = jdbc.query(
                base + " ORDER BY sa.assigned_date DESC, s.student_code LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Map<String, Object> item = new HashMap<>();
                    Date assignedDate = rs.getDate("assigned_date");
                    item.put("id", rs.getString("user_id"));
                    item.put("studentCode", rs.getString("student_code"));
                    item.put("fullName", rs.getString("full_name"));
                    item.put("avatarUrl", rs.getString("avatar_url"));
                    item.put("cohortId", rs.getString("cohort_id"));
                    item.put("cohort", rs.getString("cohort"));
                    item.put("majorCode", rs.getString("major_code"));
                    item.put("advisorId", rs.getString("advisor_id"));
                    item.put("advisorName", rs.getString("advisor_name"));
                    item.put("assignedDate", assignedDate == null ? null : assignedDate.toLocalDate().toString());
                    return item;
                }, params.toArray());
        return new Page<>(items, page, size, total == null ? 0 : total);
    }
}
