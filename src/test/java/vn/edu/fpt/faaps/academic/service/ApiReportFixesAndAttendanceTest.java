package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportHistoryItem;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportPreview;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceRule;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.CourseAttendanceOverride;
import vn.edu.fpt.faaps.ai.controller.AdminAiConfigController;
import vn.edu.fpt.faaps.ai.controller.AdminAiPerformanceController;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.AdminAiConfigService;
import vn.edu.fpt.faaps.ai.service.StudentAiService;
import vn.edu.fpt.faaps.auth.service.AuthPasswordService;
import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController;

class ApiReportFixesAndAttendanceTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:api-report-fixes-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE roles (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(20) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY,
                    role_id VARCHAR(36),
                    email VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255),
                    auth_provider VARCHAR(20),
                    external_id VARCHAR(255),
                    full_name VARCHAR(255) NOT NULL,
                    phone VARCHAR(20),
                    date_of_birth DATE,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    failed_login_attempts INT DEFAULT 0,
                    locked_until TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE password_reset_tokens (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id VARCHAR(36) NOT NULL,
                    token VARCHAR(255) UNIQUE NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    used_at TIMESTAMP,
                    ip_address VARCHAR(45),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT,
                    total_credits INT NOT NULL,
                    duration_years DECIMAL(3,1) NOT NULL,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE semesters (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    start_date DATE NOT NULL,
                    end_date DATE NOT NULL,
                    is_current BOOLEAN DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY,
                    major_id VARCHAR(36) NOT NULL,
                    version VARCHAR(50) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    effective_from_semester_id VARCHAR(36),
                    is_current BOOLEAN DEFAULT TRUE,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE students (
                    user_id VARCHAR(36) PRIMARY KEY,
                    student_code VARCHAR(50) UNIQUE,
                    major_id VARCHAR(36),
                    curriculum_version_id VARCHAR(36),
                    enrollment_semester_id VARCHAR(36),
                    expected_graduation_semester_id VARCHAR(36),
                    current_semester INT DEFAULT 1,
                    current_gpa DECIMAL(4,2) DEFAULT 0,
                    total_credits_earned INT DEFAULT 0,
                    academic_status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_categories (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    description TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    credits INT NOT NULL,
                    category_id VARCHAR(36) NOT NULL,
                    description TEXT,
                    objectives TEXT,
                    learning_outcomes TEXT,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY,
                    course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL,
                    group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE',
                    minimum_score DECIMAL(4,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY,
                    curriculum_version_id VARCHAR(36),
                    course_id VARCHAR(36),
                    semester_number INT DEFAULT 1,
                    is_mandatory BOOLEAN DEFAULT TRUE,
                    group_name VARCHAR(100)
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
        jdbc.execute("""
                CREATE TABLE ai_configurations (
                    id VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(100) UNIQUE,
                    provider VARCHAR(20),
                    model_name VARCHAR(100),
                    purpose VARCHAR(20),
                    temperature DECIMAL(3,2),
                    max_tokens INT,
                    retrieval_top_k INT,
                    similarity_threshold DECIMAL(5,4),
                    timeout_seconds INT,
                    daily_request_limit INT,
                    system_prompt TEXT,
                    fallback_message TEXT,
                    is_active BOOLEAN,
                    updated_by_user_id VARCHAR(36),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_usage_logs (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id VARCHAR(36),
                    message_id VARCHAR(36),
                    ai_configuration_id VARCHAR(36),
                    feature VARCHAR(20),
                    prompt_tokens INT,
                    completion_tokens INT,
                    total_tokens INT,
                    latency_ms INT,
                    status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE advising_sessions (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36) NOT NULL,
                    advisor_id VARCHAR(36) NOT NULL,
                    scheduled_at TIMESTAMP NOT NULL,
                    ended_at TIMESTAMP,
                    location VARCHAR(255),
                    agenda TEXT,
                    status VARCHAR(20) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE student_course_records (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36) NOT NULL,
                    course_id VARCHAR(36) NOT NULL,
                    semester_id VARCHAR(36) NOT NULL,
                    final_score DECIMAL(4,2),
                    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
                    attempt_number INT DEFAULT 1,
                    credits_earned INT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE study_plans (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36) NOT NULL,
                    semester_id VARCHAR(36) NOT NULL,
                    version INT NOT NULL,
                    is_current BOOLEAN DEFAULT TRUE,
                    previous_plan_id VARCHAR(36),
                    plan_type VARCHAR(20) DEFAULT 'REGULAR',
                    total_credits_planned INT DEFAULT 0,
                    status VARCHAR(20) DEFAULT 'DRAFT',
                    submitted_at TIMESTAMP,
                    reviewed_by_user_id VARCHAR(36),
                    reviewed_at TIMESTAMP,
                    review_notes TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (student_id, semester_id, version)
                )
                """);
        jdbc.execute("""
                CREATE TABLE study_plan_courses (
                    id VARCHAR(36) PRIMARY KEY,
                    study_plan_id VARCHAR(36) NOT NULL,
                    course_id VARCHAR(36) NOT NULL,
                    planned_credits INT DEFAULT 3,
                    priority VARCHAR(20) DEFAULT 'MEDIUM',
                    notes TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_recommendations (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36),
                    course_id VARCHAR(36),
                    semester_id VARCHAR(36),
                    recommendation_source VARCHAR(20),
                    confidence_score DECIMAL(5,4),
                    reasoning TEXT,
                    priority VARCHAR(20),
                    status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE notifications (
                    id VARCHAR(36) PRIMARY KEY,
                    recipient_user_id VARCHAR(36) NOT NULL,
                    notification_type VARCHAR(20) DEFAULT 'INFO',
                    title VARCHAR(255) NOT NULL,
                    message TEXT NOT NULL,
                    action_url VARCHAR(500),
                    related_student_id VARCHAR(36),
                    resolved_at TIMESTAMP,
                    is_read BOOLEAN DEFAULT FALSE,
                    read_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @Test
    void testB1AndB2CourseCreditsFilterAndCircularPrerequisites() {
        AdminCourseService courses = new AdminCourseService(jdbc);
        UUID catId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());

        var c2 = courses.create("HCM202", "Ho Chi Minh Ideology", 2, catId, null, null, null, "ACTIVE", List.of());
        var c3a = courses.create("PRF192", "Programming Fundamentals", 3, catId, null, null, null, "ACTIVE", List.of());
        var c3b = courses.create("PRO192", "OOP", 3, catId, null, null, null, "ACTIVE", List.of());

        // B1: Filter by credits = 2 vs credits = 3
        assertEquals(1, courses.list(null, null, null, 2, 0, 10, "code,asc").totalElements());
        assertEquals("HCM202", courses.list(null, null, null, 2, 0, 10, "code,asc").items().get(0).code());
        assertEquals(2, courses.list(null, null, null, 3, 0, 10, "code,asc").totalElements());

        // B2: PRO192 requires PRF192 -> OK; then PRF192 requires PRO192 -> 409 Conflict (circular!)
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
        jdbc.update("INSERT INTO majors (id, code, name, total_credits, duration_years, status) VALUES (?, 'SE', 'Software Engineering', 144, 4.0, 'ACTIVE')",
                majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name) VALUES (?, ?, 'K19', 'SE - K19')",
                cvId.toString(), majorId.toString());

        // B5: Create DRAFT rule, filter by status=DRAFT, update status to ACTIVE
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

        // B6: Update Major cohort
        AdminMajorService majorService = new AdminMajorService(jdbc);
        var updatedMajor = majorService.update(majorId, null, null, null, null, null, null, "K20");
        assertEquals("K20", updatedMajor.cohort());

        // B6: Filter appointments by advisorId, fromDate, toDate
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

    @Test
    void testS1RoadmapRegenerateAndExcludePassedCoursesAndS3CourseCodeInValidationAndS4PhoneSpaces() {
        UUID studentId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        UUID semOld = UUID.randomUUID();
        UUID semCurrent = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID catId = UUID.randomUUID();

        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) VALUES (?, 'SP26', 'Spring 2026', '2026-01-01', '2026-04-30', FALSE)",
                semOld.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) VALUES (?, 'FA26', 'Fall 2026', '2026-09-01', '2026-12-31', TRUE)",
                semCurrent.toString());
        jdbc.update("INSERT INTO users (id, email, full_name, phone) VALUES (?, 'student.demo@fpt.edu.vn', 'Demo Student', '0912 345 678')",
                studentId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, 'SE190001', ?)",
                studentId.toString(), cvId.toString());

        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRF192', 'Programming Fundamentals', 3, ?)",
                c1.toString(), catId.toString());
        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRO192', 'OOP', 3, ?)",
                c2.toString(), catId.toString());
        jdbc.update("INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type) VALUES (?, ?, ?, 1, 'PREREQUISITE')",
                UUID.randomUUID().toString(), c2.toString(), c1.toString());
        jdbc.update("INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory) VALUES (?, ?, ?, 1, TRUE), (?, ?, ?, 2, TRUE)",
                UUID.randomUUID().toString(), cvId.toString(), c1.toString(),
                UUID.randomUUID().toString(), cvId.toString(), c2.toString());

        // S3: Validate study plan when PRF192 is not passed yet -> message shows PRO192 & PRF192 instead of UUIDs
        CourseLearningPlanService learningPlans = new CourseLearningPlanService(jdbc);
        StudentPlanService planService = new StudentPlanService(jdbc, learningPlans);
        var savedPlan = planService.saveCurriculum(studentId, semCurrent, "REGULAR", List.of(c2));
        assertEquals(1, savedPlan.issues().size());
        assertTrue(savedPlan.issues().get(0).contains("PRO192"));
        assertTrue(savedPlan.issues().get(0).contains("PRF192"));
        assertFalse(savedPlan.issues().get(0).contains(c2.toString()));

        // Mark PRF192 as PASSED
        jdbc.update("""
                INSERT INTO student_course_records (id, student_id, course_id, semester_id, final_score, status, credits_earned)
                VALUES (?, ?, ?, ?, 8.5, 'PASSED', 3)
                """, UUID.randomUUID().toString(), studentId.toString(), c1.toString(), semOld.toString());

        // S1: Generate roadmap twice (regenerate) without DuplicateKeyException, using current semester and skipping PASSED course PRF192
        StudentAiService aiService = new StudentAiService(jdbc, null);
        StudyRoadmapDto firstRoadmap = aiService.generateRoadmap(studentId, new GenerateRoadmapDto("On time", 20));
        StudyRoadmapDto secondRoadmap = aiService.generateRoadmap(studentId, new GenerateRoadmapDto("Fast track", 20));
        assertNotNull(firstRoadmap.id());
        assertNotNull(secondRoadmap.id());
        assertEquals(3, secondRoadmap.totalCredits()); // Only PRO192 (3 credits), PRF192 was skipped!
        assertEquals(semCurrent.toString(),
                jdbc.queryForObject("SELECT semester_id FROM study_plans WHERE id = ?", String.class, secondRoadmap.id()));

        // S4: Profile read strips spaces from legacy seeded phone, and updateProfile accepts spaced phone
        StudentAcademicService academicService = new StudentAcademicService(jdbc);
        assertEquals("0912345678", academicService.profile(studentId).phone());
        var updatedProfile = academicService.updateProfile(studentId, "Demo Student Updated", "0912 345 678");
        assertEquals("0912345678", updatedProfile.phone());
        assertEquals(2, academicService.coursesPaged("", 0, 20).totalElements());
    }

    @Test
    void testForgotResetAndChangePasswordFlows() {
        AuthPasswordService passwordService = new AuthPasswordService(jdbc);
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, full_name, status, auth_provider)
                VALUES (?, 'student.demo@fpt.edu.vn', 'Student Demo', 'ACTIVE', 'LOCAL')
                """, userId.toString());

        var forgot = passwordService.forgotPassword("student.demo@fpt.edu.vn", "127.0.0.1");
        assertTrue(forgot.success());
        assertNotNull(forgot.resetToken());

        // Reset password using the token
        passwordService.resetPassword(forgot.resetToken(), "NewPass123");

        // Reusing the same token fails
        assertThrows(IllegalArgumentException.class,
                () -> passwordService.resetPassword(forgot.resetToken(), "AnotherPass123"));

        // Change password while logged in
        assertThrows(IllegalArgumentException.class,
                () -> passwordService.changePassword(userId, "WrongCurrent123", "ChangedPass456"));
        passwordService.changePassword(userId, "NewPass123", "ChangedPass456");
    }

    @Test
    void testAttendanceConfigImportAndOnChangedDeltaNotification() throws Exception {
        AdminAttendanceService attendance = new AdminAttendanceService(jdbc);

        UUID studentId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID semId = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());
        jdbc.update("INSERT INTO users (id, email, full_name, status) VALUES (?, 'se173012@fpt.edu.vn', 'Student A', 'ACTIVE')",
                studentId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code) VALUES (?, 'SE173012')", studentId.toString());
        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRF192', 'Programming Fundamentals', 3, ?)",
                courseId.toString(), catId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) VALUES (?, 'FA25', 'Fall 2025', '2025-09-01', '2025-12-31', TRUE)",
                semId.toString());

        // Default config & Course override
        AttendanceRule defaultRule = attendance.saveDefaultRule(new AttendanceRule(20, 80, 3));
        assertEquals(20, defaultRule.totalSessions());

        CourseAttendanceOverride override = attendance.saveOverride(courseId, new AttendanceRule(20, 80, 3));
        assertEquals("PRF192", override.courseCode());
        assertEquals(1, attendance.listOverrides().size());

        // 1st Import: 3 absent sessions out of 20 total sessions (15.00% absent -> WARNING)
        String csv1 = "Student ID,Semester,Course code,Total sessions,Absent sessions\nSE173012,FA25,PRF192,20,3\n";
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "att1.csv", "text/csv", csv1.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview1 = attendance.previewImport(file1);
        assertEquals(1, preview1.validRows());
        assertEquals(0, preview1.errorRows());
        assertEquals("WARNING", preview1.rows().get(0).result());

        AttendanceImportHistoryItem confirmed1 = attendance.confirmImport(UUID.fromString(preview1.importId()));
        assertEquals(1, confirmed1.rowCount());
        assertEquals(1, confirmed1.warningCount());

        // Verify OnChanged fired once (1 notification inserted)
        Integer notifCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(1, notifCountAfterFirst);

        // 2nd Import with UNCHANGED attendance (still 3/20 = 15.00% absent): OnChanged MUST NOT fire!
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "att2.csv", "text/csv", csv1.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview2 = attendance.previewImport(file2);
        attendance.confirmImport(UUID.fromString(preview2.importId()));

        Integer notifCountAfterUnchanged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(1, notifCountAfterUnchanged, "Unchanged attendance import must NOT trigger a new notification");

        // 3rd Import with INCREASED absence (5/20 = 25.00% absent -> FAIL): OnChanged MUST fire!
        String csv3 = "Student ID,Semester,Course code,Total sessions,Absent sessions\nSE173012,FA25,PRF192,20,5\n";
        MockMultipartFile file3 = new MockMultipartFile(
                "file", "att3.csv", "text/csv", csv3.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview3 = attendance.previewImport(file3);
        AttendanceImportHistoryItem confirmed3 = attendance.confirmImport(UUID.fromString(preview3.importId()));
        assertEquals(1, confirmed3.failCount());

        Integer notifCountAfterIncrease = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(2, notifCountAfterIncrease, "Increased % absent over total sessions must trigger OnChanged notification");
        assertEquals(3, attendance.listImports().size());
    }
}
