package vn.edu.fpt.faaps.user.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;

final class AdminUserTestSupport {

    record Fixture(JdbcTemplate jdbc, AdminUserService users, UUID adminId, UUID studentId) {
    }

    private AdminUserTestSupport() {
    }

    static Fixture createFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-users-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("""
                CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255),
                    password_hash VARCHAR(255), failed_login_attempts INT DEFAULT 0, locked_until TIMESTAMP,
                    auth_provider VARCHAR(20), external_id VARCHAR(255), email_verified_at TIMESTAMP,
                    full_name VARCHAR(255), phone VARCHAR(20), status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), "
                + "major_id VARCHAR(36), curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36), "
                + "expected_graduation_semester_id VARCHAR(36), current_semester INT, current_gpa DECIMAL(4,2), "
                + "total_credits_earned INT, academic_status VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY, max_advisees INT, status VARCHAR(20), "
                + "assignment_start_date DATE, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), "
                + "version VARCHAR(50), name VARCHAR(100), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(100), "
                + "start_date DATE, end_date DATE)");
        jdbc.execute("CREATE TABLE curriculum_courses (id VARCHAR(36) PRIMARY KEY, "
                + "curriculum_version_id VARCHAR(36), semester_number INT)");
        jdbc.execute("CREATE TABLE student_advisor_assignments (student_id VARCHAR(36), advisor_id VARCHAR(36), "
                + "status VARCHAR(20), end_date DATE, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE cohort_advisors (cohort_id VARCHAR(36), advisor_id VARCHAR(36))");

        String adminRole = UUID.randomUUID().toString();
        String studentRole = UUID.randomUUID().toString();
        String advisorRole = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", adminRole);
        jdbc.update("INSERT INTO roles VALUES (?, 'STUDENT')", studentRole);
        jdbc.update("INSERT INTO roles VALUES (?, 'ADVISOR')", advisorRole);
        UUID adminId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'admin@example.com', 'Admin', 'ACTIVE')",
                adminId.toString(), adminRole);
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'student@example.com', 'Student', 'ACTIVE')",
                studentId.toString(), studentRole);
        AdminUserRepository repository = new AdminUserRepository(jdbc);
        AdminUserService users = new AdminUserService(jdbc, repository, new UserRoleService(jdbc, repository));
        return new Fixture(jdbc, users, adminId, studentId);
    }
}
