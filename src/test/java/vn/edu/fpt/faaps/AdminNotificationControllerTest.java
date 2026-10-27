package vn.edu.fpt.faaps;

import vn.edu.fpt.faaps.controller.AdminNotificationController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.auth.model.AuthSession;

class AdminNotificationControllerTest {
    private JdbcTemplate jdbc;
    private AdminNotificationController controller;
    private AuthSession admin;
    private AuthSession otherAdmin;
    private UUID unreadId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:notifications-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36), student_code VARCHAR(30), curriculum_version_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), recipient_user_id VARCHAR(36), notification_type VARCHAR(50), title VARCHAR(100), message VARCHAR(255), action_url VARCHAR(255), is_read BOOLEAN, related_student_id VARCHAR(36), resolved_at TIMESTAMP, created_at TIMESTAMP, read_at TIMESTAMP)");
        admin = new AuthSession(UUID.randomUUID(), "Admin", "admin@example.com", "ADMIN", "ACTIVE");
        otherAdmin = new AuthSession(UUID.randomUUID(), "Other", "other@example.com", "ADMIN", "ACTIVE");
        unreadId = UUID.randomUUID();
        jdbc.update("INSERT INTO students VALUES ('student-1', 'SE001', 'cohort-1')");
        jdbc.update("INSERT INTO notifications VALUES (?, ?, 'ADVISOR_REQUEST', 'Request', 'Assign advisor', '/admin/assignments', FALSE, 'student-1', NULL, CURRENT_TIMESTAMP, NULL)", unreadId.toString(), admin.id().toString());
        jdbc.update("INSERT INTO notifications VALUES (?, ?, 'ADVISOR_REQUEST', 'Read', 'Done', '/admin/assignments', TRUE, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), admin.id().toString());
        jdbc.update("INSERT INTO notifications VALUES (?, ?, 'ADVISOR_REQUEST', 'Other', 'Private', '/admin/assignments', FALSE, NULL, NULL, CURRENT_TIMESTAMP, NULL)", UUID.randomUUID().toString(), otherAdmin.id().toString());
        controller = new AdminNotificationController(jdbc);
    }

    @Test
    void paginatesUnreadNotificationsAndIncludesStudentContext() {
        var page = controller.list(admin, true, 0, 1);
        assertEquals(1L, page.get("totalElements"));
        assertEquals(1L, page.get("totalPages"));
        var items = (java.util.List<?>) page.get("items");
        var item = (java.util.Map<?, ?>) items.getFirst();
        assertEquals("SE001", item.get("studentCode"));
        assertEquals("cohort-1", item.get("cohortId"));
        assertThrows(IllegalArgumentException.class, () -> controller.list(admin, false, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> controller.list(admin, false, 0, 101));
    }

    @Test
    void readOperationsCannotAffectAnotherAdminsNotifications() {
        assertEquals(false, controller.markRead(otherAdmin, unreadId).get("updated"));
        assertEquals(true, controller.markRead(admin, unreadId).get("updated"));
        assertEquals(false, controller.markRead(admin, unreadId).get("updated"));
        assertEquals(0, controller.markAllRead(admin).get("updated"));
        assertEquals(1, controller.markAllRead(otherAdmin).get("updated"));
    }
}

