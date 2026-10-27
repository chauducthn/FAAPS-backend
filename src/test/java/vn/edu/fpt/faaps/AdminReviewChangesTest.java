package vn.edu.fpt.faaps;

import vn.edu.fpt.faaps.controller.AdvisorScheduleController;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.auth.model.AuthSession;

class AdminReviewChangesTest {
    private JdbcTemplate jdbc;
    private AdvisorScheduleController schedules;
    private AuthSession admin;
    private UUID advisorId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:review-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36), code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36), role_id VARCHAR(36), full_name VARCHAR(100), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36), assignment_start_date DATE, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), recipient_user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(255), action_url VARCHAR(255), related_student_id VARCHAR(36), is_read BOOLEAN, resolved_at TIMESTAMP)");
        String roleId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", roleId);
        admin = new AuthSession(UUID.randomUUID(), "Admin A", "admin@example.com", "ADMIN", "ACTIVE");
        advisorId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, ?, 'Admin A', 'ACTIVE')", admin.id().toString(), roleId);
        jdbc.update("INSERT INTO advisors (user_id) VALUES (?)", advisorId.toString());
        schedules = new AdvisorScheduleController(jdbc);
    }

    @Test
    void storesAdvisorAssignmentStartDate() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        schedules.update(advisorId, new AdvisorScheduleController.UpdateSchedule(date), admin);
        assertEquals(date, schedules.get(advisorId).getBody().assignmentStartDate());
        assertEquals(404, schedules.get(UUID.randomUUID()).getStatusCode().value());
    }
}

