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
                    full_name VARCHAR(255), phone VARCHAR(20), avatar_url VARCHAR(500), status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), major_id VARCHAR(36), curriculum_version_id VARCHAR(36), current_semester INT, academic_status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50))");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, version VARCHAR(50))");
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
        AdminUserService.Page page = users.list("STUDENT", "ACTIVE", "student", 0, 20, "userId,asc");
        assertEquals(1, page.totalElements());
        assertEquals(studentId, page.items().get(0).id());
        assertEquals("student@example.com", page.items().get(0).userId());
        assertEquals("STUDENT", users.detail(studentId).role());
    }

    @Test
    void updatesProfileAndProtectsOwnAdminAccount() {
        users.update(studentId, "New Name", "123", "https://example.com/avatar.png");
        assertEquals("New Name", users.detail(studentId).fullName());
        assertThrows(AdminUserService.Conflict.class, () ->
                users.changeStatus(List.of(adminId), "SUSPENDED", adminId));
        assertEquals(1, users.changeStatus(List.of(studentId), "SUSPENDED", adminId));
        assertEquals("SUSPENDED", users.detail(studentId).status());
        assertEquals(1, users.changeStatus(List.of(studentId), "ACTIVE", adminId));
        assertThrows(IllegalArgumentException.class, () ->
                users.update(studentId, "Student", null, "javascript:alert(1)"));
    }
}
