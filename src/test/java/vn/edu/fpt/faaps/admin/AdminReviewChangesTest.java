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
import vn.edu.fpt.faaps.auth.AuthSession;

class AdminReviewChangesTest {
    private JdbcTemplate jdbc;
    private AdminHandoverController handovers;
    private AdvisorScheduleController schedules;
    private AuthSession admin;
    private UUID successorId;
    private UUID advisorId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:review-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36), code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36), role_id VARCHAR(36), full_name VARCHAR(100), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36), work_days VARCHAR(20), assignment_start_date DATE, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE admin_handovers (id VARCHAR(36), from_admin_id VARCHAR(36), to_admin_id VARCHAR(36), handover_date DATE, notes VARCHAR(255), recorded_by_user_id VARCHAR(36), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE admin_control (control_id TINYINT PRIMARY KEY, current_admin_id VARCHAR(36), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), recipient_user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(255), action_url VARCHAR(255), related_student_id VARCHAR(36), is_read BOOLEAN, resolved_at TIMESTAMP)");
        String roleId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", roleId);
        admin = new AuthSession(UUID.randomUUID(), "Admin A", "admin@example.com", "ADMIN", "ACTIVE");
        successorId = UUID.randomUUID();
        advisorId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, ?, 'Admin A', 'ACTIVE')", admin.id().toString(), roleId);
        jdbc.update("INSERT INTO users VALUES (?, ?, 'Admin B', 'ACTIVE')", successorId.toString(), roleId);
        jdbc.update("INSERT INTO admin_control (control_id, current_admin_id) VALUES (1, ?)", admin.id().toString());
        jdbc.update("INSERT INTO advisors (user_id) VALUES (?)", advisorId.toString());
        handovers = new AdminHandoverController(jdbc);
        schedules = new AdvisorScheduleController(jdbc);
    }

    @Test
    void recordsHandoverBetweenAdmins() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        var record = handovers.create(new AdminHandoverController.CreateHandover(
                admin.id(), successorId, date, "Review handover"), admin);
        assertEquals(date, record.handoverDate());
        assertEquals(1, handovers.list().size());
        assertEquals(successorId, handovers.current().id());
        assertThrows(IllegalArgumentException.class, () -> handovers.create(
                new AdminHandoverController.CreateHandover(admin.id(), admin.id(), date, null), admin));
        assertThrows(IllegalArgumentException.class, () -> handovers.create(
                new AdminHandoverController.CreateHandover(admin.id(), advisorId, date, null), admin));
        assertThrows(IllegalArgumentException.class, () -> handovers.create(
                new AdminHandoverController.CreateHandover(admin.id(), successorId, date, null), admin));
    }

    @Test
    void rejectsFutureHandover() {
        assertThrows(IllegalArgumentException.class, () -> handovers.create(
                new AdminHandoverController.CreateHandover(admin.id(), successorId,
                        LocalDate.now().plusDays(1), null), admin));
        assertEquals(admin.id(), handovers.current().id());
    }

    @Test
    void rejectsInactiveIncomingAdminWithoutChangingCurrentAdmin() {
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", successorId.toString());
        assertThrows(IllegalArgumentException.class, () -> handovers.create(
                new AdminHandoverController.CreateHandover(admin.id(), successorId,
                        LocalDate.now(), null), admin));
        assertEquals(admin.id(), handovers.current().id());
    }

    @Test
    void transfersPendingAdvisorRequestsToIncomingAdmin() {
        UUID studentId = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications VALUES (?, ?, 'ADVISOR_REQUEST', 'Request', 'Help', NULL, ?, FALSE, NULL)",
                UUID.randomUUID().toString(), admin.id().toString(), studentId.toString());
        handovers.create(new AdminHandoverController.CreateHandover(admin.id(), successorId,
                LocalDate.now(), null), admin);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id=? AND resolved_at IS NULL",
                Integer.class, admin.id().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id=? AND resolved_at IS NULL",
                Integer.class, successorId.toString()));
    }

    @Test
    void storesValidatedAdvisorSchedule() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        var response = schedules.update(advisorId, new AdvisorScheduleController.UpdateSchedule(
                List.of(5, 1, 3), date), admin);
        assertEquals(List.of(1, 3, 5), response.getBody().workDays());
        assertEquals(date, schedules.get(advisorId).getBody().assignmentStartDate());
        assertThrows(IllegalArgumentException.class, () -> schedules.update(advisorId,
                new AdvisorScheduleController.UpdateSchedule(List.of(1, 1), date), admin));
        assertEquals(404, schedules.get(UUID.randomUUID()).getStatusCode().value());
    }
}
