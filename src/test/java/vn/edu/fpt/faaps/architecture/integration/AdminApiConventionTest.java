package vn.edu.fpt.faaps.architecture.integration;

import vn.edu.fpt.faaps.auth.repository.AuthAccountStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.edu.fpt.faaps.auth.model.AuthAccount;
import vn.edu.fpt.faaps.auth.model.GoogleIdentity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-api-conv;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Import(AdminApiConventionTest.TestDbConfig.class)
class AdminApiConventionTest {

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeAccountStore accountStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private URI baseUrl;
    private UUID semesterId;
    private UUID curriculumVersionId;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        baseUrl = URI.create("http://localhost:" + port);

        jdbcTemplate.execute("DROP ALL OBJECTS");

        jdbcTemplate.execute("""
                CREATE TABLE roles (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY,
                    role_id VARCHAR(36),
                    email VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255),
                    auth_provider VARCHAR(20),
                    external_id VARCHAR(255),
                    email_verified_at TIMESTAMP,
                    full_name VARCHAR(255) NOT NULL,
                    phone VARCHAR(20),
                    status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE refresh_tokens (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
                    token VARCHAR(500) UNIQUE NOT NULL, expires_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP, ip_address VARCHAR(45), user_agent VARCHAR(500),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
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
        jdbcTemplate.execute("""
                CREATE TABLE semesters (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    start_date DATE NOT NULL,
                    end_date DATE NOT NULL,
                    is_current BOOLEAN DEFAULT false,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY,
                    major_id VARCHAR(36) NOT NULL,
                    version VARCHAR(50) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    effective_from_semester_id VARCHAR(36),
                    effective_to_semester_id VARCHAR(36),
                    is_current BOOLEAN DEFAULT false,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE students (
                    user_id VARCHAR(36) PRIMARY KEY,
                    student_code VARCHAR(50) UNIQUE NOT NULL,
                    major_id VARCHAR(36) NOT NULL,
                    curriculum_version_id VARCHAR(36) NOT NULL,
                    enrollment_semester_id VARCHAR(36) NOT NULL,
                    academic_status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE course_categories (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    description TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
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
        jdbcTemplate.execute("""
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
        jdbcTemplate.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY,
                    curriculum_version_id VARCHAR(36),
                    course_id VARCHAR(36)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE academic_policies (
                    id VARCHAR(36) PRIMARY KEY,
                    curriculum_version_id VARCHAR(36) NOT NULL,
                    policy_type VARCHAR(30) NOT NULL,
                    condition_expression TEXT,
                    restriction_expression TEXT,
                    approval_required BOOLEAN DEFAULT false,
                    priority INT DEFAULT 0,
                    description TEXT,
                    display_message TEXT,
                    is_active BOOLEAN DEFAULT true,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        semesterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2026', 'Spring 2026', '2026-01-01', '2026-04-30')",
                semesterId.toString());

        categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'SPEC', 'Specialized Knowledge')",
                categoryId.toString());

        curriculumVersionId = UUID.randomUUID();
        UUID majorSeedId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO majors (id, code, name, total_credits, duration_years) VALUES (?, 'SE_SEED', 'Software Engineering Seed', 140, 4.0)",
                majorSeedId.toString());
        jdbcTemplate.update("INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id) VALUES (?, ?, 'K18', 'K18 Curriculum', ?)",
                curriculumVersionId.toString(), majorSeedId.toString(), semesterId.toString());
    }

    @Test
    void securityConventionRoleChecksAndUnauthenticatedAccess() throws Exception {
        HttpClient anonClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        // 1. Unauthenticated requests to admin endpoints return 401
        assertEquals(401, send(anonClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors")).GET().build()).statusCode());
        assertEquals(401, send(anonClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses")).GET().build()).statusCode());
        assertEquals(401, send(anonClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/users")).GET().build()).statusCode());
        assertEquals(401, send(anonClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules")).GET().build()).statusCode());

        // 2. Non-admin login attempts to admin login endpoint return 401
        AuthAccount student = new AuthAccount(UUID.randomUUID(), "student@example.com", "Student User",
                "STUDENT", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(student);

        String csrf = getCsrfToken(anonClient);
        HttpResponse<String> studentLogin = send(anonClient, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/admin/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"student@example.com\",\"password\":\"secret\"}"))
                .build());
        assertEquals(401, studentLogin.statusCode());

        // 3. Authenticated Admin accessing endpoints of another role returns 403 Forbidden
        HttpClient adminClient = loginAsAdmin();
        assertEquals(403, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/student/dashboard")).GET().build()).statusCode());
        assertEquals(403, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/advisor/dashboard")).GET().build()).statusCode());
    }

    @Test
    void validationErrorReturns400WithCodeMessageAndFieldErrors() throws Exception {
        HttpClient adminClient = loginAsAdmin();
        String csrf = getCsrfToken(adminClient);

        // 1. Major validation error
        HttpRequest invalidMajorReq = HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"\",\"name\":\"\",\"totalCredits\":-5}"))
                .build();
        HttpResponse<String> majorResp = send(adminClient, invalidMajorReq);
        assertEquals(400, majorResp.statusCode());
        assertTrue(majorResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(majorResp.body().contains("\"message\":\"Validation failed\""));
        assertTrue(majorResp.body().contains("\"fieldErrors\""));
        assertTrue(majorResp.body().contains("\"code\""));

        // 2. Course validation error
        HttpRequest invalidCourseReq = HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"\",\"name\":\"\",\"credits\":0}"))
                .build();
        HttpResponse<String> courseResp = send(adminClient, invalidCourseReq);
        assertEquals(400, courseResp.statusCode());
        assertTrue(courseResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(courseResp.body().contains("\"fieldErrors\""));

        // 3. Academic rule validation error
        HttpRequest invalidRuleReq = HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"policyType\":\"\"}"))
                .build();
        HttpResponse<String> ruleResp = send(adminClient, invalidRuleReq);
        assertEquals(400, ruleResp.statusCode());
        assertTrue(ruleResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(ruleResp.body().contains("\"fieldErrors\""));
    }

    @Test
    void dataConflictReturns409CodeAndMessage() throws Exception {
        HttpClient adminClient = loginAsAdmin();
        String csrf = getCsrfToken(adminClient);

        // 1. Duplicate major code returns 409
        String createMajorJson = """
                {"code":"IA","name":"Information Assurance","totalCredits":140,"durationYears":4.0,"status":"ACTIVE"}
                """;
        HttpResponse<String> firstMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(createMajorJson))
                .build());
        assertEquals(201, firstMajor.statusCode());

        csrf = getCsrfToken(adminClient);
        HttpResponse<String> duplicateMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(createMajorJson))
                .build());
        assertEquals(409, duplicateMajor.statusCode());
        assertTrue(duplicateMajor.body().contains("\"code\":\"CONFLICT\""));

        // 2. Duplicate course code returns 409
        String createCourseJson = """
                {"code":"PRJ301","name":"Java Web Application","credits":3,"categoryId":"%s"}
                """.formatted(categoryId);
        csrf = getCsrfToken(adminClient);
        HttpResponse<String> firstCourse = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(createCourseJson))
                .build());
        assertEquals(201, firstCourse.statusCode());

        csrf = getCsrfToken(adminClient);
        HttpResponse<String> duplicateCourse = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(createCourseJson))
                .build());
        assertEquals(409, duplicateCourse.statusCode());
        assertTrue(duplicateCourse.body().contains("\"code\":\"CONFLICT\""));

        // 3. Deleting a major attached to curriculum version returns 409 Conflict
        String majorWithCurriculum = """
                {"code":"SE_COHORT","name":"Software Eng Cohort","totalCredits":140,"durationYears":4.0,"status":"ACTIVE","cohort":"K19"}
                """;
        csrf = getCsrfToken(adminClient);
        HttpResponse<String> curMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(majorWithCurriculum))
                .build());
        assertEquals(201, curMajor.statusCode());
        String curMajorId = extractJsonString(curMajor.body(), "id");

        csrf = getCsrfToken(adminClient);
        HttpResponse<String> deleteCurConflict = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + curMajorId))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE().build());
        assertEquals(409, deleteCurConflict.statusCode());
        assertTrue(deleteCurConflict.body().contains("\"code\":\"CONFLICT\""));
    }

    @Test
    void notFoundReturns404CodeAndMessage() throws Exception {
        HttpClient adminClient = loginAsAdmin();
        UUID nonExistentId = UUID.randomUUID();

        HttpResponse<String> major404 = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + nonExistentId)).GET().build());
        assertEquals(404, major404.statusCode());
        assertTrue(major404.body().contains("\"code\":\"NOT_FOUND\""));

        HttpResponse<String> course404 = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses/" + nonExistentId)).GET().build());
        assertEquals(404, course404.statusCode());
        assertTrue(course404.body().contains("\"code\":\"NOT_FOUND\""));

        HttpResponse<String> rule404 = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules/" + nonExistentId)).GET().build());
        assertEquals(404, rule404.statusCode());
        assertTrue(rule404.body().contains("\"code\":\"NOT_FOUND\""));
    }

    @Test
    void listApiAdheresToPaginationSortingAndTotalElementsConvention() throws Exception {
        HttpClient adminClient = loginAsAdmin();

        HttpResponse<String> majorOptions = send(adminClient, HttpRequest.newBuilder(
                baseUrl.resolve("/api/admin/majors")).GET().build());
        assertEquals(200, majorOptions.statusCode());
        assertTrue(majorOptions.body().startsWith("["));

        // 1. Majors list
        HttpResponse<String> majorsList = send(adminClient, HttpRequest.newBuilder(
                baseUrl.resolve("/api/admin/majors/paged?search=&status=ACTIVE&page=0&size=10&sort=code,asc")).GET().build());
        assertEquals(200, majorsList.statusCode());
        assertTrue(majorsList.body().contains("\"totalElements\""));
        assertTrue(majorsList.body().contains("\"totalPages\""));
        assertTrue(majorsList.body().contains("\"page\""));
        assertTrue(majorsList.body().contains("\"size\""));
        assertTrue(majorsList.body().contains("\"items\""));

        // 2. Courses list
        HttpResponse<String> coursesList = send(adminClient, HttpRequest.newBuilder(
                baseUrl.resolve("/api/admin/courses?search=&status=ACTIVE&page=0&size=10&sort=code,asc")).GET().build());
        assertEquals(200, coursesList.statusCode());
        assertTrue(coursesList.body().contains("\"totalElements\""));
        assertTrue(coursesList.body().contains("\"items\""));

        // 3. Academic rules list
        HttpResponse<String> rulesList = send(adminClient, HttpRequest.newBuilder(
                baseUrl.resolve("/api/admin/academic-rules?page=0&size=10")).GET().build());
        assertEquals(200, rulesList.statusCode());
        assertTrue(rulesList.body().contains("\"totalElements\""));
        assertTrue(rulesList.body().contains("\"items\""));
    }

    @Test
    void fullCrudFlowAcrossAllThreeModules() throws Exception {
        HttpClient adminClient = loginAsAdmin();
        String csrf = getCsrfToken(adminClient);

        // --- MODULE 1: MAJORS CRUD ---
        String majorJson = """
                {"code":"AI","name":"Artificial Intelligence","totalCredits":145,"durationYears":4.0,"status":"ACTIVE"}
                """;
        HttpResponse<String> createdMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(majorJson))
                .build());
        assertEquals(201, createdMajor.statusCode());
        String majorId = extractJsonString(createdMajor.body(), "id");

        // Detail
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + majorId)).GET().build()).statusCode());

        // Update
        csrf = getCsrfToken(adminClient);
        HttpResponse<String> updatedMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + majorId))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"AI & Data Science\"}"))
                .build());
        assertEquals(200, updatedMajor.statusCode());
        assertTrue(updatedMajor.body().contains("AI & Data Science"));

        // Delete
        csrf = getCsrfToken(adminClient);
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + majorId))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE().build()).statusCode());

        // --- MODULE 2: COURSES CRUD ---
        csrf = getCsrfToken(adminClient);
        String courseJson = """
                {"code":"SWE201","name":"Software Engineering Fundamentals","credits":3,"categoryId":"%s","status":"ACTIVE"}
                """.formatted(categoryId);
        HttpResponse<String> createdCourse = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(courseJson))
                .build());
        assertEquals(201, createdCourse.statusCode());
        String courseId = extractJsonString(createdCourse.body(), "id");

        // Categories list
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/course-categories")).GET().build()).statusCode());

        // Detail
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses/" + courseId)).GET().build()).statusCode());

        // Delete
        csrf = getCsrfToken(adminClient);
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/courses/" + courseId))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE().build()).statusCode());

        // --- MODULE 3: ACADEMIC RULES CRUD ---
        csrf = getCsrfToken(adminClient);
        String ruleJson = """
                {"curriculumVersionId":"%s","policyType":"MAX_CREDITS_PER_SEMESTER","conditionExpression":"{\\"max\\":20}","displayMessage":"Max 20 credits per term"}
                """.formatted(curriculumVersionId);
        HttpResponse<String> createdRule = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(ruleJson))
                .build());
        assertEquals(201, createdRule.statusCode());
        String ruleId = extractJsonString(createdRule.body(), "id");

        // Supported types list
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules/types")).GET().build()).statusCode());

        // Status Toggle (PATCH)
        csrf = getCsrfToken(adminClient);
        HttpResponse<String> toggleResp = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules/" + ruleId + "/status"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"isActive\":false}"))
                .build());
        assertEquals(200, toggleResp.statusCode());
        assertTrue(toggleResp.body().contains("\"isActive\":false"));

        // Delete
        csrf = getCsrfToken(adminClient);
        assertEquals(200, send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules/" + ruleId))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE().build()).statusCode());
    }

    private HttpClient loginAsAdmin() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        AuthAccount admin = new AuthAccount(UUID.randomUUID(), "admin@example.com", "Admin User",
                "ADMIN", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(admin);

        String csrf = getCsrfToken(client);
        HttpResponse<String> login = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/admin/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .build());
        assertEquals(200, login.statusCode());
        return client;
    }

    private String getCsrfToken(HttpClient client) throws Exception {
        HttpResponse<String> resp = send(client, HttpRequest.newBuilder(baseUrl.resolve("/api/auth/csrf")).GET().build());
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(resp.body());
        assertTrue(m.find());
        return m.group(1);
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    @TestConfiguration
    static class TestDbConfig {
        @Bean
        public FakeAccountStore fakeAccountStore() {
            return new FakeAccountStore();
        }
    }

    static class FakeAccountStore implements AuthAccountStore {
        final AtomicReference<AuthAccount> account = new AtomicReference<>();

        @Override
        public Optional<AuthAccount> findById(UUID id) {
            return Optional.ofNullable(account.get()).filter(c -> c.id().equals(id));
        }

        @Override
        public Optional<AuthAccount> findAdminByEmail(String email) {
            return Optional.ofNullable(account.get()).filter(c -> c.email().equals(email));
        }

        @Override
        public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
            return account.get();
        }
    }
}
