package vn.edu.fpt.faaps.academic.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAcademicRuleService {
    public static final List<String> POLICY_TYPES = List.of(
            "ADVANCED_STUDY", "RETAKE", "MAX_CREDITS_PER_SEMESTER",
            "MIN_CREDITS_PER_SEMESTER", "COURSE_REPEAT_LIMIT", "GPA_WARNING",
            "ACADEMIC_PROBATION", "OTHER"
    );

    private final JdbcTemplate jdbc;

    public AdminAcademicRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String search, String status, String policyType, UUID curriculumVersionId,
            int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL") && !List.of("ACTIVE", "INACTIVE").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND ap.is_active = ?");
            params.add(status.equalsIgnoreCase("ACTIVE"));
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
            where.append(" AND (LOWER(ap.policy_type) LIKE ? OR LOWER(ap.description) LIKE ? OR LOWER(ap.display_message) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
        }

        String countSql = "SELECT COUNT(*) FROM academic_policies ap" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;

        String orderBy = " ORDER BY ap.priority DESC, ap.created_at DESC, ap.id DESC";
        if (sort != null && !sort.isBlank()) {
            orderBy = switch (sort) {
                case "priority,asc" -> " ORDER BY ap.priority ASC, ap.id ASC";
                case "policyType,asc" -> " ORDER BY ap.policy_type ASC, ap.id ASC";
                case "policyType,desc" -> " ORDER BY ap.policy_type DESC, ap.id DESC";
                case "createdAt,asc" -> " ORDER BY ap.created_at ASC, ap.id ASC";
                default -> " ORDER BY ap.priority DESC, ap.created_at DESC, ap.id DESC";
            };
        }

        String sql = """
                SELECT ap.id, ap.curriculum_version_id, cv.version AS curriculum_version, cv.name AS curriculum_name,
                       m.code AS major_code, m.name AS major_name,
                       ap.policy_type, ap.condition_expression, ap.restriction_expression,
                       ap.approval_required, ap.priority, ap.description, ap.display_message,
                       ap.is_active, ap.created_at, ap.updated_at
                FROM academic_policies ap
                LEFT JOIN curriculum_versions cv ON cv.id = ap.curriculum_version_id
                LEFT JOIN majors m ON m.id = cv.major_id
                """ + where + orderBy + " LIMIT ? OFFSET ?";

        params.add(size);
        params.add(page * size);

        List<RuleSummary> items = jdbc.query(sql, (rs, rowNum) -> mapRule(rs), params.toArray());
        long totalPages = (totalElements + size - 1) / size;
        return new Page(items, page, size, totalElements, totalPages);
    }

    public RuleSummary detail(UUID id) {
        String sql = """
                SELECT ap.id, ap.curriculum_version_id, cv.version AS curriculum_version, cv.name AS curriculum_name,
                       m.code AS major_code, m.name AS major_name,
                       ap.policy_type, ap.condition_expression, ap.restriction_expression,
                       ap.approval_required, ap.priority, ap.description, ap.display_message,
                       ap.is_active, ap.created_at, ap.updated_at
                FROM academic_policies ap
                LEFT JOIN curriculum_versions cv ON cv.id = ap.curriculum_version_id
                LEFT JOIN majors m ON m.id = cv.major_id
                WHERE ap.id = ?
                """;
        List<RuleSummary> results = jdbc.query(sql, (rs, rowNum) -> mapRule(rs), id.toString());
        if (results.isEmpty()) {
            throw new NotFound();
        }
        return results.get(0);
    }

    @Transactional
    public RuleSummary create(UUID curriculumVersionId, String policyType, String conditionExpression,
            String restrictionExpression, Boolean approvalRequired, Integer priority,
            String description, String displayMessage, Boolean isActive) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException("curriculumVersionId is required");
        }
        if (policyType == null || policyType.isBlank()) {
            throw new IllegalArgumentException("policyType is required");
        }
        String type = policyType.strip().toUpperCase();
        if (!POLICY_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown policyType: " + policyType);
        }

        UUID id = UUID.randomUUID();
        boolean active = isActive == null || isActive;
        boolean approval = approvalRequired != null && approvalRequired;
        int prio = priority != null ? priority : 0;

        jdbc.update("""
                INSERT INTO academic_policies (id, curriculum_version_id, policy_type, condition_expression,
                    restriction_expression, approval_required, priority, description, display_message,
                    is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id.toString(), curriculumVersionId.toString(), type, conditionExpression,
                restrictionExpression, approval, prio, description, displayMessage, active);

        return detail(id);
    }

    @Transactional
    public RuleSummary update(UUID id, UUID curriculumVersionId, String policyType,
            String conditionExpression, String restrictionExpression, Boolean approvalRequired,
            Integer priority, String description, String displayMessage, Boolean isActive) {
        RuleSummary current = detail(id);
        UUID newCvId = curriculumVersionId != null ? curriculumVersionId : current.curriculumVersionId();
        String newType = current.policyType();
        if (policyType != null && !policyType.isBlank()) {
            String upper = policyType.strip().toUpperCase();
            if (POLICY_TYPES.contains(upper)) {
                newType = upper;
            }
        }
        String newCond = conditionExpression != null ? conditionExpression : current.conditionExpression();
        String newRest = restrictionExpression != null ? restrictionExpression : current.restrictionExpression();
        boolean newApproval = approvalRequired != null ? approvalRequired : current.approvalRequired();
        int newPrio = priority != null ? priority : current.priority();
        String newDesc = description != null ? description : current.description();
        String newMsg = displayMessage != null ? displayMessage : current.displayMessage();
        boolean newActive = isActive != null ? isActive : current.isActive();

        jdbc.update("""
                UPDATE academic_policies
                SET curriculum_version_id = ?, policy_type = ?, condition_expression = ?,
                    restriction_expression = ?, approval_required = ?, priority = ?,
                    description = ?, display_message = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                newCvId.toString(), newType, newCond, newRest, newApproval, newPrio,
                newDesc, newMsg, newActive, id.toString());

        return detail(id);
    }

    @Transactional
    public RuleSummary updateStatus(UUID id, boolean isActive) {
        detail(id);
        jdbc.update("UPDATE academic_policies SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                isActive, id.toString());
        return detail(id);
    }

    @Transactional
    public void delete(UUID id) {
        detail(id);
        jdbc.update("DELETE FROM academic_policies WHERE id = ?", id.toString());
    }

    public List<String> types() {
        return POLICY_TYPES;
    }

    private RuleSummary mapRule(ResultSet rs) throws SQLException {
        return new RuleSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("curriculum_version_id") != null ? UUID.fromString(rs.getString("curriculum_version_id")) : null,
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
                rs.getBoolean("is_active"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
        );
    }

    public record RuleSummary(UUID id, UUID curriculumVersionId, String curriculumVersion, String curriculumName,
            String majorCode, String majorName, String policyType, String conditionExpression,
            String restrictionExpression, boolean approvalRequired, int priority, String description,
            String displayMessage, boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record Page(List<RuleSummary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
    }
}

