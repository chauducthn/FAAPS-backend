package vn.edu.fpt.faaps.academic.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminAcademicRuleService.NotFound;
import vn.edu.fpt.faaps.academic.service.AdminAcademicRuleService.Page;
import vn.edu.fpt.faaps.academic.service.AdminAcademicRuleService.RuleSummary;

final class AdminAcademicRuleQuery {
    private static final Logger log = LoggerFactory.getLogger(AdminAcademicRuleQuery.class);

    private static final String BASE_SELECT = """
            SELECT ap.id, ap.curriculum_version_id, cv.version AS curriculum_version, cv.name AS curriculum_name,
                   m.code AS major_code, m.name AS major_name,
                   ap.policy_type, ap.condition_expression, ap.restriction_expression,
                   ap.approval_required, ap.priority, ap.description, ap.display_message,
                   ap.is_active, ap.created_at, ap.updated_at
            FROM academic_policies ap
            LEFT JOIN curriculum_versions cv ON cv.id = ap.curriculum_version_id
            LEFT JOIN majors m ON m.id = cv.major_id
            """;

    private final JdbcTemplate jdbc;

    AdminAcademicRuleQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page list(String search, String status, String policyType, UUID curriculumVersionId,
            int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL")
                && !List.of("ACTIVE", "INACTIVE", "DRAFT").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilters(where, params, search, status, policyType, curriculumVersionId);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic_policies ap" + where, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;
        String sql = BASE_SELECT + where + resolveOrderBy(sort) + " LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);

        List<RuleSummary> items = jdbc.query(sql, (rs, rowNum) -> mapRule(rs), params.toArray());
        return new Page(items, page, size, totalElements, (totalElements + size - 1) / size);
    }

    RuleSummary detail(UUID id) {
        List<RuleSummary> results = jdbc.query(BASE_SELECT + " WHERE ap.id = ?",
                (rs, rowNum) -> mapRule(rs), id.toString());
        if (results.isEmpty()) {
            throw new NotFound();
        }
        return results.get(0);
    }

    UUID resolveDefaultCurriculumVersionId() {
        try {
            List<String> ids = jdbc.query(
                    "SELECT id FROM curriculum_versions ORDER BY is_current DESC, created_at DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!ids.isEmpty() && ids.get(0) != null) {
                return UUID.fromString(ids.get(0));
            }
        } catch (Exception ex) {
            log.debug("Could not resolve current curriculum version: {}", ex.getMessage());
        }
        try {
            List<String> ids = jdbc.query("SELECT id FROM curriculum_versions LIMIT 1", (rs, row) -> rs.getString(1));
            if (!ids.isEmpty() && ids.get(0) != null) {
                return UUID.fromString(ids.get(0));
            }
        } catch (Exception ex) {
            log.debug("Could not resolve fallback curriculum version: {}", ex.getMessage());
        }
        return null;
    }

    private void appendFilters(StringBuilder where, List<Object> params,
            String search, String status, String policyType, UUID curriculumVersionId) {
        if (status != null && !status.equalsIgnoreCase("ALL")) {
            if (status.equalsIgnoreCase("DRAFT")) {
                where.append(" AND ap.is_active IS NULL");
            } else {
                where.append(" AND ap.is_active = ?");
                params.add(status.equalsIgnoreCase("ACTIVE"));
            }
        }
        if (policyType != null && !policyType.isBlank() && !policyType.equalsIgnoreCase("ALL")) {
            where.append(" AND ap.policy_type = ?");
            params.add(policyType.toUpperCase());
        }
        if (curriculumVersionId != null) {
            where.append(" AND ap.curriculum_version_id = ?");
            params.add(curriculumVersionId.toString());
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(ap.policy_type) LIKE ? OR LOWER(ap.description) LIKE ?"
                    + " OR LOWER(ap.display_message) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }
    }

    private String resolveOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return " ORDER BY ap.priority DESC, ap.created_at DESC, ap.id DESC";
        }
        return switch (sort) {
            case "priority,asc" -> " ORDER BY ap.priority ASC, ap.id ASC";
            case "policyType,asc" -> " ORDER BY ap.policy_type ASC, ap.id ASC";
            case "policyType,desc" -> " ORDER BY ap.policy_type DESC, ap.id DESC";
            case "createdAt,asc" -> " ORDER BY ap.created_at ASC, ap.id ASC";
            default -> " ORDER BY ap.priority DESC, ap.created_at DESC, ap.id DESC";
        };
    }

    private RuleSummary mapRule(ResultSet rs) throws SQLException {
        Object activeObj = rs.getObject("is_active");
        String status = activeObj == null ? "DRAFT" : (rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE");
        boolean isActive = "ACTIVE".equals(status);
        String cvId = rs.getString("curriculum_version_id");
        return new RuleSummary(
                UUID.fromString(rs.getString("id")),
                cvId != null ? UUID.fromString(cvId) : null,
                rs.getString("curriculum_version"),
                rs.getString("curriculum_name"),
                rs.getString("major_code"),
                rs.getString("major_name"),
                rs.getString("policy_type"),
                rs.getString("condition_expression"),
                rs.getString("restriction_expression"),
                rs.getBoolean("approval_required"),
                rs.getInt("priority"),
                rs.getString("description"),
                rs.getString("display_message"),
                isActive,
                status,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
    }
}
