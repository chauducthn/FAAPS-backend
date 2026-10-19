package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminAcademicRuleServiceTest {
    private JdbcTemplate jdbc;
    private AdminAcademicRuleService rules;
    private UUID cvId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-rules-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50),
                    name VARCHAR(255)
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY,
                    major_id VARCHAR(36),
                    version VARCHAR(50),
                    name VARCHAR(255)
                )
                """);
        jdbc.execute("""
                CREATE TABLE academic_policies (
                    id VARCHAR(36) PRIMARY KEY,
                    curriculum_version_id VARCHAR(36) NOT NULL,
                    policy_type VARCHAR(30) NOT NULL,
                    condition_expression TEXT,
                    restriction_expression TEXT,
                    approval_required BOOLEAN DEFAULT FALSE,
                    priority INT DEFAULT 0,
                    description TEXT,
                    display_message TEXT,
                    is_active BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        UUID majorId = UUID.randomUUID();
        cvId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name) VALUES (?, 'SE', 'Software Engineering')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name) VALUES (?, ?, 'K18', 'SE K18')",
                cvId.toString(), majorId.toString());

        rules = new AdminAcademicRuleService(jdbc);
    }

    @Test
    void createsAndListsAcademicRules() {
        AdminAcademicRuleService.RuleSummary rule = rules.create(
                cvId, "MAX_CREDITS_PER_SEMESTER", "{\"max_credits\": 24}", null, false, 10,
                "Maximum 24 credits per regular semester", "You cannot enroll in more than 24 credits", true);
        assertNotNull(rule.id());
        assertEquals("MAX_CREDITS_PER_SEMESTER", rule.policyType());
        assertEquals("K18", rule.curriculumVersion());
        assertEquals("SE", rule.majorCode());

        AdminAcademicRuleService.Page page = rules.list(null, "ACTIVE", null, cvId, 0, 10, null);
        assertEquals(1, page.totalElements());
    }

    @Test
    void updatesRuleStatusAndDeletes() {
        AdminAcademicRuleService.RuleSummary rule = rules.create(
                cvId, "GPA_WARNING", "{\"min_gpa\": 5.0}", null, false, 5,
                "GPA below 5.0 triggers warning", "Warning: low GPA", true);
        assertTrue(rule.isActive());

        AdminAcademicRuleService.RuleSummary updated = rules.updateStatus(rule.id(), false);
        assertFalse(updated.isActive());

        rules.delete(rule.id());
        assertThrows(AdminAcademicRuleService.NotFound.class, () -> rules.detail(rule.id()));
    }
}
