package vn.edu.fpt.faaps.advising.service;

import vn.edu.fpt.faaps.advising.service.AdminAssignmentService;
import vn.edu.fpt.faaps.advising.service.AssignmentRequestService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AssignmentRequestServiceTest {
    private JdbcTemplate jdbc;
    private AssignmentRequestService requests;
    private UUID studentId;
    private UUID advisorId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:assignment-requests-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36), code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36), role_id VARCHAR(36), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36), student_code VARCHAR(50), curriculum_version_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE student_advisor_assignments (student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), end_date DATE)");
        jdbc.execute("CREATE TABLE advising_requests (id VARCHAR(36), student_id VARCHAR(36), advisor_id VARCHAR(36), request_type VARCHAR(30), title VARCHAR(255), description VARCHAR(255), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36), recipient_user_id VARCHAR(36), notification_type VARCHAR(20), title VARCHAR(255), message VARCHAR(255), action_url VARCHAR(255), is_read BOOLEAN, related_student_id VARCHAR(36), resolved_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE admin_account (singleton_id TINYINT PRIMARY KEY, user_id VARCHAR(36) UNIQUE)");
        studentId = UUID.randomUUID();
        advisorId = UUID.randomUUID();
        UUID adminRole = UUID.randomUUID();
        jdbc.update("INSERT INTO roles VALUES (?, 'ADMIN')", adminRole.toString());
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, ?, 'ACTIVE')", adminId.toString(), adminRole.toString());
        jdbc.update("INSERT INTO admin_account VALUES (1, ?)", adminId.toString());
        jdbc.update("INSERT INTO students VALUES (?, 'S0001', ?)", studentId.toString(), UUID.randomUUID().toString());
        requests = new AssignmentRequestService(jdbc);
    }

    @Test
    void pendingAssignmentRequestIsIdempotentAndNotifiesAdminOnce() {
        Map<String, Object> first = requests.requestAdvisor(studentId);
        Map<String, Object> second = requests.requestAdvisor(studentId);
        assertEquals(true, first.get("created"));
        assertEquals(false, second.get("created"));
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("id"), requests.assignmentStatus(studentId).get("pendingRequestId"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
        assertThrows(AdminAssignmentService.Conflict.class, () -> requests.requestAdvising(studentId, "Help", "Courses"));
        assertEquals(false, requests.assignmentStatus(studentId).get("canRequestAdvisor"));
    }

    @Test
    void advisingRequestRequiresAnAdvisor() {
        assertThrows(AdminAssignmentService.Conflict.class, () -> requests.requestAdvising(studentId, "Help", "Courses"));
        jdbc.update("INSERT INTO student_advisor_assignments VALUES (?, ?, 'ACTIVE', NULL)",
                studentId.toString(), advisorId.toString());
        assertEquals("PENDING", requests.requestAdvising(studentId, "Help", "Courses").get("status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM advising_requests", Integer.class));
    }
}

