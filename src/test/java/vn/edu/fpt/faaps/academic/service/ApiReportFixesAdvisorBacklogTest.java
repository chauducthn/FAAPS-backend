package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.controller.AdvisorAcademicLookupController;
import vn.edu.fpt.faaps.advising.controller.AdvisorReportController;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

class ApiReportFixesAdvisorBacklogTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ApiReportFixesTestSchema.createJdbc();
    }

    @Test
    void testAdvisorBacklogEndpointsAtRiskLookupReportsAndGraduationCheck() {
        jdbc.execute("""
                CREATE TABLE student_advisor_assignments (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL,
                    advisor_id VARCHAR(36) NOT NULL, group_name VARCHAR(100),
                    assigned_date DATE, end_date DATE, is_primary BOOLEAN DEFAULT TRUE,
                    status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbc.execute("""
                CREATE TABLE advising_requests (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL,
                    advisor_id VARCHAR(36), study_plan_id VARCHAR(36),
                    title VARCHAR(255) NOT NULL, description TEXT,
                    request_type VARCHAR(30) DEFAULT 'ACADEMIC_GUIDANCE',
                    status VARCHAR(20) DEFAULT 'PENDING', resolution_summary TEXT,
                    resolved_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE advising_messages (
                    id VARCHAR(36) PRIMARY KEY, advising_request_id VARCHAR(36) NOT NULL,
                    sender_user_id VARCHAR(36) NOT NULL, content TEXT NOT NULL,
                    is_internal BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        UUID advisorId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID majorId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID semId = UUID.randomUUID();
        String studentRole = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO roles VALUES (?, 'STUDENT')", studentRole);
        jdbc.update("INSERT INTO users (id, email, full_name, status) VALUES (?, 'advisor@fpt.edu.vn', 'Advisor One', 'ACTIVE')",
                advisorId.toString());
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) "
                + "VALUES (?, ?, 'student.risk@fpt.edu.vn', 'At Risk Student', 'ACTIVE')",
                studentId.toString(), studentRole);
        jdbc.update("INSERT INTO majors (id, code, name, total_credits, duration_years, status) "
                + "VALUES (?, 'SE', 'Software Engineering', 144, 4.0, 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name, status) VALUES (?, ?, 'K19', 'SE K19', 'ACTIVE')",
                cvId.toString(), majorId.toString());
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());
        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id, status) "
                + "VALUES (?, 'PRF192', 'Programming Fundamentals', 3, ?, 'ACTIVE')",
                courseId.toString(), catId.toString());
        jdbc.update("INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory) "
                + "VALUES (?, ?, ?, 1, TRUE)", UUID.randomUUID().toString(), cvId.toString(), courseId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) "
                + "VALUES (?, 'FA26', 'Fall 2026', '2026-09-01', '2026-12-31', TRUE)", semId.toString());
        jdbc.update("""
                INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
                    current_semester, current_gpa, total_credits_earned, academic_status)
                VALUES (?, 'SE190999', ?, ?, 3, 4.20, 30, 'PROBATION')
                """, studentId.toString(), majorId.toString(), cvId.toString());
        jdbc.update("""
                INSERT INTO student_advisor_assignments (id, student_id, advisor_id, assigned_date, is_primary, status)
                VALUES (?, ?, ?, CURRENT_DATE, TRUE, 'ACTIVE')
                """, UUID.randomUUID().toString(), studentId.toString(), advisorId.toString());

        var lookup = new AdvisorAcademicLookupController(jdbc);
        assertEquals(1, lookup.getCourses("PRF", "CORE").size());
        assertEquals("PRF192", lookup.getCourseDetail(courseId).code());
        assertEquals(1, lookup.getCurriculums().size());

        var atRiskService = new AdvisorAtRiskService(jdbc);
        var atRiskResp = atRiskService.list(advisorId, null, null, 0, 100);
        assertEquals(1, atRiskResp.total());
        assertEquals("HIGH", atRiskResp.data().get(0).riskLevel());
        assertTrue(atRiskResp.data().get(0).reasons().contains("LOW_GPA"));
        assertEquals(1, atRiskService.counts(advisorId).high());

        var authSession = new AuthSession(advisorId, "Advisor One", "advisor@fpt.edu.vn", "ADVISOR", "ACTIVE");
        var reportController = new AdvisorReportController(jdbc, atRiskService);
        var overview = reportController.overview(authSession, null, null);
        assertEquals(1, overview.totalStudents());
        assertEquals(1, overview.atRiskStudents());
        assertEquals(1, overview.probationCount());
        assertEquals(1, reportController.statusDistribution(authSession).get("PROBATION"));

        String reqId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO advising_requests (id, student_id, advisor_id, title, description, request_type, status)
                VALUES (?, ?, ?, 'Need help', 'Course selection', 'COURSE_SELECTION', 'PENDING')
                """, reqId, studentId.toString(), advisorId.toString());
        var convService = new AdvisingConversationService(jdbc);
        var rejected = convService.changeStatus(advisorId, true, UUID.fromString(reqId), "CANCELLED", "Rejected with reason");
        assertEquals("CANCELLED", rejected.request().status());
        assertEquals("SE190999", rejected.request().studentCode());
    }
}
