package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                    full_name VARCHAR(255), phone VARCHAR(20), status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), major_id VARCHAR(36), curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36), current_semester INT, academic_status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50))");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), version VARCHAR(50), name VARCHAR(100))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(100), start_date DATE)");
        jdbc.execute("CREATE TABLE student_advisor_assignments (student_id VARCHAR(36), status VARCHAR(20), end_date DATE)");
        String adminRole = UUID.randomUUID().toString();
        String studentRole = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", adminRole);
        jdbc.update("INSERT INTO roles VALUES (?, 'STUDENT')", studentRole);
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
}
