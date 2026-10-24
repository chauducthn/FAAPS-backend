package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminUserServiceTest {
    private JdbcTemplate jdbc;
    private AdminUserService users;
    private UUID adminId;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-users-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("""
                CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255),
                    auth_provider VARCHAR(20), external_id VARCHAR(255), email_verified_at TIMESTAMP,
                    full_name VARCHAR(255), phone VARCHAR(20), status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), major_id VARCHAR(36), curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36), expected_graduation_semester_id VARCHAR(36), current_semester INT, current_gpa DECIMAL(4,2), total_credits_earned INT, academic_status VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, max_advisees INT, status VARCHAR(20), assignment_start_date DATE, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), version VARCHAR(50), name VARCHAR(100), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(100), start_date DATE, end_date DATE)");
        jdbc.execute("CREATE TABLE student_advisor_assignments (student_id VARCHAR(36), status VARCHAR(20), end_date DATE)");
        String adminRole = UUID.randomUUID().toString();
        String studentRole = UUID.randomUUID().toString();
        String advisorRole = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", adminRole);
        jdbc.update("INSERT INTO roles VALUES (?, 'STUDENT')", studentRole);
        jdbc.update("INSERT INTO roles VALUES (?, 'ADVISOR')", advisorRole);
        adminId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'admin@example.com', 'Admin', 'ACTIVE')",
                adminId.toString(), adminRole);
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'student@example.com', 'Student', 'ACTIVE')",
                studentId.toString(), studentRole);
        users = new AdminUserService(jdbc);
    }

    @Test
    void listsUsersWithoutProfilesAndFiltersByRole() {
        AdminUserService.Page page = users.list("STUDENT", "ACTIVE", "student", null, 0, 20, "userId,asc");
        assertEquals(1, page.totalElements());
        assertEquals(studentId, page.items().get(0).id());
        assertEquals("student@example.com", page.items().get(0).userId());
        assertEquals("STUDENT", users.detail(studentId).role());
    }

    @Test
    void listsMajorsAndFiltersStudentsByMajor() {
        UUID softwareMajor = UUID.randomUUID();
        UUID aiMajor = UUID.randomUUID();
        UUID aiStudent = UUID.randomUUID();
        String studentRole = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'STUDENT'", String.class);
        jdbc.update("INSERT INTO majors (id, code, name) VALUES (?, 'SE', 'Software Engineering')",
                softwareMajor.toString());
        jdbc.update("INSERT INTO majors (id, code, name) VALUES (?, 'AI', 'Artificial Intelligence')",
                aiMajor.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id) VALUES (?, 'SE01', ?)",
                studentId.toString(), softwareMajor.toString());
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'ai@example.com', 'AI Student', 'ACTIVE')",
                aiStudent.toString(), studentRole);
        jdbc.update("INSERT INTO students (user_id, student_code, major_id) VALUES (?, 'AI01', ?)",
                aiStudent.toString(), aiMajor.toString());

        List<AdminOverviewController.Major> majors = new AdminOverviewController(jdbc).majors();
        assertEquals(List.of("AI", "SE"), majors.stream().map(AdminOverviewController.Major::code).toList());
        assertEquals(softwareMajor, majors.get(1).id());

        AdminUserService.Page filtered = users.list("STUDENT", null, null, softwareMajor,
                0, 20, "userId,asc");
        assertEquals(1, filtered.totalElements());
        assertEquals(studentId, filtered.items().get(0).id());
        assertEquals(3, users.list(null, null, null, null, 0, 20, "userId,asc").totalElements());
    }

    @Test
    void protectsOwnAdminAccountAndChangesStudentStatus() {
        assertThrows(AdminUserService.Conflict.class, () ->
                users.changeStatus(List.of(adminId), "SUSPENDED", adminId));
        assertEquals(1, users.changeStatus(List.of(studentId), "SUSPENDED", adminId));
        assertEquals("SUSPENDED", users.detail(studentId).status());
        assertEquals(1, users.changeStatus(List.of(studentId), "ACTIVE", adminId));
    }

    @Test
    void rejectsInvalidBulkStatusInputsWithoutNullPointerException() {
        assertThrows(IllegalArgumentException.class, () -> users.changeStatus(List.of(studentId), null, adminId));
        assertThrows(IllegalArgumentException.class, () -> users.changeStatus(null, "ACTIVE", adminId));
        assertThrows(IllegalArgumentException.class, () ->
                users.changeStatus(java.util.Arrays.asList(studentId, null), "ACTIVE", adminId));
    }

    @Test
    void createsStudentAccountAndAcademicProfileAtomically() {
        UUID majorId = UUID.randomUUID();
        UUID curriculumId = UUID.randomUUID();
        UUID enrollmentSemesterId = UUID.randomUUID();
        UUID graduationSemesterId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, status) VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')",
                majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name, status) VALUES (?, ?, 'K20', 'SE K20', 'ACTIVE')",
                curriculumId.toString(), majorId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2026', 'Spring 2026', ?, ?)",
                enrollmentSemesterId.toString(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2030', 'Spring 2030', ?, ?)",
                graduationSemesterId.toString(), LocalDate.of(2030, 1, 1), LocalDate.of(2030, 4, 30));

        AdminUserService.Detail created = users.create(new AdminUserService.CreateAccount(
                "STUDENT", "new.student@example.com", "New Student", "0900000000",
                "SE20001", curriculumId, enrollmentSemesterId, graduationSemesterId, 1, null));

        assertEquals("STUDENT", created.role());
        assertEquals("SE20001", created.userId());
        assertEquals(majorId.toString(), created.majorId());
        assertEquals(curriculumId.toString(), created.curriculumVersionId());
        assertEquals(graduationSemesterId.toString(), created.expectedGraduationSemesterId());
        assertEquals("GOOGLE", jdbc.queryForObject("SELECT auth_provider FROM users WHERE id = ?",
                String.class, created.id().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id = ?",
                Integer.class, created.id().toString()));
    }

    @Test
    void createsAdvisorAccountWithDefaultCapacity() {
        AdminUserService.Detail created = users.create(new AdminUserService.CreateAccount(
                "ADVISOR", "new.advisor@example.com", "New Advisor", null,
                null, null, null, null, null, null));

        assertEquals("ADVISOR", created.role());
        assertEquals("new.advisor@example.com", created.userId());
        assertEquals("new.advisor@example.com", created.email());
        assertEquals(30, jdbc.queryForObject("SELECT max_advisees FROM advisors WHERE user_id = ?",
                Integer.class, created.id().toString()));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM advisors WHERE user_id = ?",
                String.class, created.id().toString()));
    }
}
