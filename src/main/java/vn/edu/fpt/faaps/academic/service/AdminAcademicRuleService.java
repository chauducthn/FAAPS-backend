package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDateTime;
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
    private final AdminAcademicRuleQuery query;

    public AdminAcademicRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.query = new AdminAcademicRuleQuery(jdbc);
    }

    public Page list(String search, String status, String policyType, UUID curriculumVersionId,
            int page, int size, String sort) {
        return query.list(search, status, policyType, curriculumVersionId, page, size, sort);
    }

    public RuleSummary detail(UUID id) {
        return query.detail(id);
    }

    @Transactional
    public RuleSummary create(UUID curriculumVersionId, String policyType, String conditionExpression,
            String restrictionExpression, Boolean approvalRequired, Integer priority,
            String description, String displayMessage, Boolean isActive) {
        return create(curriculumVersionId, policyType, conditionExpression, restrictionExpression,
                approvalRequired, priority, description, displayMessage, isActive, null);
    }

    @Transactional
    public RuleSummary create(UUID curriculumVersionId, String policyType, String conditionExpression,
            String restrictionExpression, Boolean approvalRequired, Integer priority,
            String description, String displayMessage, Boolean isActive, String status) {
        UUID effectiveCvId = curriculumVersionId != null
                ? curriculumVersionId : query.resolveDefaultCurriculumVersionId();
        if (effectiveCvId == null) {
            throw new IllegalArgumentException("curriculumVersionId is required when no curriculum versions exist");
        }
        if (policyType == null || policyType.isBlank()) {
            throw new IllegalArgumentException("policyType is required");
        }
        String type = policyType.strip().toUpperCase();
        if (!POLICY_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown policyType: " + policyType);
        }

        UUID id = UUID.randomUUID();
        Boolean active = resolveActiveFlag(status, isActive, Boolean.TRUE);
        boolean approval = approvalRequired != null && approvalRequired;
        int prio = priority != null ? priority : 0;

        jdbc.update("""
                INSERT INTO academic_policies (id, curriculum_version_id, policy_type, condition_expression,
                    restriction_expression, approval_required, priority, description, display_message,
                    is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id.toString(), effectiveCvId.toString(), type, conditionExpression,
                restrictionExpression, approval, prio, description, displayMessage, active);
        return detail(id);
    }

    @Transactional
    public RuleSummary update(UUID id, UUID curriculumVersionId, String policyType,
            String conditionExpression, String restrictionExpression, Boolean approvalRequired,
            Integer priority, String description, String displayMessage, Boolean isActive) {
        return update(id, curriculumVersionId, policyType, conditionExpression, restrictionExpression,
                approvalRequired, priority, description, displayMessage, isActive, null);
    }

    @Transactional
    public RuleSummary update(UUID id, UUID curriculumVersionId, String policyType,
            String conditionExpression, String restrictionExpression, Boolean approvalRequired,
            Integer priority, String description, String displayMessage, Boolean isActive, String status) {
        RuleSummary current = detail(id);
        UUID newCvId = curriculumVersionId != null ? curriculumVersionId : current.curriculumVersionId();
        String newType = current.policyType();
        if (policyType != null && !policyType.isBlank()) {
            String upper = policyType.strip().toUpperCase();
            if (POLICY_TYPES.contains(upper)) {
                newType = upper;
            }
        }
        Boolean currentActive = "DRAFT".equalsIgnoreCase(current.status()) ? null : current.isActive();
        Boolean newActive = resolveActiveFlag(status, isActive, currentActive);

        jdbc.update("""
                UPDATE academic_policies
                SET curriculum_version_id = ?, policy_type = ?, condition_expression = ?,
                    restriction_expression = ?, approval_required = ?, priority = ?,
                    description = ?, display_message = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                newCvId.toString(), newType,
                conditionExpression != null ? conditionExpression : current.conditionExpression(),
                restrictionExpression != null ? restrictionExpression : current.restrictionExpression(),
                approvalRequired != null ? approvalRequired : current.approvalRequired(),
                priority != null ? priority : current.priority(),
                description != null ? description : current.description(),
                displayMessage != null ? displayMessage : current.displayMessage(),
                newActive, id.toString());
        return detail(id);
    }

    @Transactional
    public RuleSummary updateStatus(UUID id, boolean isActive) {
        return updateStatus(id, isActive, null);
    }

    @Transactional
    public RuleSummary updateStatus(UUID id, Boolean isActive, String status) {
        RuleSummary current = detail(id);
        Boolean currentActive = "DRAFT".equalsIgnoreCase(current.status()) ? null : current.isActive();
        Boolean newActive = resolveActiveFlag(status, isActive, currentActive);
        jdbc.update("UPDATE academic_policies SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                newActive, id.toString());
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

    private Boolean resolveActiveFlag(String status, Boolean isActive, Boolean fallback) {
        if (status != null && !status.isBlank()) {
            return switch (status.strip().toUpperCase()) {
                case "ACTIVE" -> Boolean.TRUE;
                case "INACTIVE" -> Boolean.FALSE;
                case "DRAFT" -> null;
                default -> throw new IllegalArgumentException("status is invalid");
            };
        }
        return isActive != null ? isActive : fallback;
    }

    public record RuleSummary(UUID id, UUID curriculumVersionId, String curriculumVersion, String curriculumName,
            String majorCode, String majorName, String policyType, String conditionExpression,
            String restrictionExpression, boolean approvalRequired, int priority, String description,
            String displayMessage, boolean isActive, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public RuleSummary(UUID id, UUID curriculumVersionId, String curriculumVersion, String curriculumName,
                String majorCode, String majorName, String policyType, String conditionExpression,
                String restrictionExpression, boolean approvalRequired, int priority, String description,
                String displayMessage, boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, curriculumVersionId, curriculumVersion, curriculumName, majorCode, majorName, policyType,
                    conditionExpression, restrictionExpression, approvalRequired, priority, description,
                    displayMessage, isActive, isActive ? "ACTIVE" : "INACTIVE", createdAt, updatedAt);
        }
    }

    public record Page(List<RuleSummary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
    }
}
