package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.controller.AdminAiConfigController;
import vn.edu.fpt.faaps.ai.controller.AdminAiPerformanceController;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.ai.service.AdminAiConfigService;
import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController;

class ApiReportFixesAdminTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ApiReportFixesTestSchema.createJdbc();
    }

    @Test
    void testB1AndB2CourseCreditsFilterAndCircularPrerequisites() {
        AdminCourseService courses = new AdminCourseService(jdbc);
        UUID catId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());

        courses.create("HCM202", "Ho Chi Minh Ideology", 2, catId, null, null, null, "ACTIVE", List.of());
        var c3a = courses.create("PRF192", "Programming Fundamentals", 3, catId, null, null, null, "ACTIVE", List.of());
        var c3b = courses.create("PRO192", "OOP", 3, catId, null, null, null, "ACTIVE", List.of());

        assertEquals(1, courses.list(null, null, null, 2, 0, 10, "code,asc").totalElements());
        assertEquals("HCM202", courses.list(null, null, null, 2, 0, 10, "code,asc").items().get(0).code());
        assertEquals(2, courses.list(null, null, null, 3, 0, 10, "code,asc").totalElements());

        courses.addRelation(c3b.id(), "PREREQUISITE", 1, c3a.id(), new BigDecimal("5.00"));
        assertThrows(AdminCourseService.Conflict.class,
                () -> courses.addRelation(c3a.id(), "PREREQUISITE", 1, c3b.id(), new BigDecimal("5.00")));
    }

    @Test
    void testB4AiConfigAndPerformanceWrappedInSuccessDataEnvelope() {
        AdminAiConfigService aiService = new AdminAiConfigService(jdbc);
        AdminAiConfigController aiController = new AdminAiConfigController(aiService);
        AdminAiPerformanceController perfController = new AdminAiPerformanceController(aiService);

        var presetsResp = aiController.getPresets();
        assertTrue(presetsResp.success());
        assertEquals(3, presetsResp.data().size());

        var createdResp = aiController.create(new CreateAiConfigDto(
                "Gemini Advisor", "GEMINI", "gemini-2.5-flash", "CHAT",
                0.7, 2048, 5, null, 30, 100, "Prompt", "Fallback", true), null);
        assertTrue(createdResp.success());
        assertNotNull(createdResp.data().id());

        var listResp = aiController.list("Gemini", "ALL", "ALL", null, 0, 10, "name,asc");
        assertTrue(listResp.success());
        assertEquals(1, listResp.data().totalItems());
        assertEquals(1, listResp.data().items().size());

        var getResp = aiController.get(UUID.fromString(createdResp.data().id()));
        assertTrue(getResp.success());
        assertEquals("Gemini Advisor", getResp.data().name());

        var perfResp = perfController.getPerformance(null, null, null, null);
        assertTrue(perfResp.success());
        assertNotNull(perfResp.data().summary());
    }

    @Test
    void testB5AcademicRuleDraftStatusAndB6MajorCohortAndAppointmentsFilter() {
        UUID majorId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, total_credits, duration_years, status) "
                + "VALUES (?, 'SE', 'Software Engineering', 144, 4.0, 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name) VALUES (?, ?, 'K19', 'SE - K19')",
                cvId.toString(), majorId.toString());

        AdminAcademicRuleService rules = new AdminAcademicRuleService(jdbc);
        var draftRule = rules.create(cvId, "GPA_WARNING", "{\"min_gpa\":5.0}", null, false, 1,
                "Draft warning", "Low GPA", null, "DRAFT");
        assertEquals("DRAFT", draftRule.status());
        assertFalse(draftRule.isActive());

        assertEquals(1, rules.list(null, "DRAFT", null, cvId, 0, 10, null).totalElements());
        assertEquals(0, rules.list(null, "ACTIVE", null, cvId, 0, 10, null).totalElements());

        var activated = rules.updateStatus(draftRule.id(), null, "ACTIVE");
        assertEquals("ACTIVE", activated.status());
        assertTrue(activated.isActive());

        AdminMajorService majorService = new AdminMajorService(jdbc);
        var updatedMajor = majorService.update(majorId, null, null, null, null, null, null, "K20");
        assertEquals("K20", updatedMajor.cohort());

        UUID student1 = UUID.randomUUID();
        UUID advisor1 = UUID.randomUUID();
        UUID advisor2 = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, full_name) VALUES (?, 's1@fpt.edu.vn', 'Student 1')", student1.toString());
        jdbc.update("INSERT INTO users (id, email, full_name) VALUES (?, 'a1@fpt.edu.vn', 'Advisor 1')", advisor1.toString());
        jdbc.update("INSERT INTO users (id, email, full_name) VALUES (?, 'a2@fpt.edu.vn', 'Advisor 2')", advisor2.toString());
        jdbc.update("""
                INSERT INTO advising_sessions (id, student_id, advisor_id, scheduled_at, status)
                VALUES (?, ?, ?, TIMESTAMP '2026-11-10 09:00:00', 'SCHEDULED'),
                       (?, ?, ?, TIMESTAMP '2026-11-20 14:00:00', 'SCHEDULED')
                """,
                UUID.randomUUID().toString(), student1.toString(), advisor1.toString(),
                UUID.randomUUID().toString(), student1.toString(), advisor2.toString());

        AdminOverviewController overview = new AdminOverviewController(jdbc);
        assertEquals(1, overview.appointments(null, null, advisor1, null, null, 0, 20).totalElements());
        assertEquals(1, overview.appointments(null, null, null, "2026-11-15", "2026-11-25", 0, 20).totalElements());
    }
}
