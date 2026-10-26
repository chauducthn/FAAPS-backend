package vn.edu.fpt.faaps.admin.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentRequestService {
    private final JdbcTemplate jdbc;
    private AdminNotificationPublisher notificationPublisher;

    public AssignmentRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Autowired(required = false)
    public void setNotificationPublisher(AdminNotificationPublisher notificationPublisher) {
        this.notificationPublisher = notificationPublisher;
    }

    @Transactional
    public Map<String, Object> requestAdvisor(UUID studentId) {
        List<String> students = jdbc.query("SELECT user_id FROM students WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), studentId.toString());
        if (students.isEmpty()) throw new IllegalArgumentException("Student profile not found");
        Map<String, Object> student = jdbc.queryForMap("SELECT student_code,curriculum_version_id FROM students WHERE user_id=?", studentId.toString());
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, Integer.class, studentId.toString());
        if (active != null && active > 0) throw new AdminAssignmentService.Conflict();
        List<String> pending = jdbc.query("""
                SELECT id FROM notifications
                WHERE related_student_id = ? AND notification_type = 'ADVISOR_REQUEST'
                  AND resolved_at IS NULL ORDER BY created_at, id LIMIT 1
                """, (result, row) -> result.getString(1), studentId.toString());
        if (!pending.isEmpty()) return Map.of("id", pending.get(0), "status", "PENDING", "created", false);
        List<String> admins = jdbc.query("""
                SELECT u.id FROM admin_account a JOIN users u ON u.id = a.user_id
                JOIN roles r ON r.id = u.role_id
                WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE'
                """, (result, row) -> result.getString(1));
        if (admins.isEmpty()) throw new AdminAssignmentService.Conflict();
        String firstId = null;
        for (String adminId : admins) {
            String id = UUID.randomUUID().toString();
            if (firstId == null) firstId = id;
            jdbc.update("""
                    INSERT INTO notifications (id, recipient_user_id, notification_type, title, message,
                                               action_url, related_student_id, is_read)
                    VALUES (?, ?, 'ADVISOR_REQUEST', 'Advisor assignment requested',
                            'A student needs an advisor assignment.', '/admin/assignments', ?, FALSE)
                    """, id, adminId, studentId.toString());
        }
        if (notificationPublisher != null) notificationPublisher.advisorRequestCreated(
                (String) student.get("student_code"), (String) student.get("curriculum_version_id"));
        return Map.of("id", firstId, "status", "PENDING", "created", true);
    }

    public Map<String, Object> assignmentStatus(UUID studentId) {
        List<String> advisors = activeAdvisors(studentId);
        if (advisors.size() > 1) throw new AdminAssignmentService.Conflict();
        List<String> pending = jdbc.query("""
                SELECT id FROM notifications
                WHERE related_student_id = ? AND notification_type = 'ADVISOR_REQUEST'
                  AND resolved_at IS NULL ORDER BY created_at, id LIMIT 1
                """, (result, row) -> result.getString(1), studentId.toString());
        java.util.HashMap<String, Object> status = new java.util.HashMap<>();
        status.put("advisorId", advisors.isEmpty() ? null : advisors.get(0));
        status.put("pendingRequestId", pending.isEmpty() ? null : pending.get(0));
        status.put("canRequestAdvisor", advisors.isEmpty() && pending.isEmpty());
        status.put("canRequestAdvising", !advisors.isEmpty());
        return status;
    }

    @Transactional
    public Map<String, Object> requestAdvising(UUID studentId, String title, String description) {
        List<String> advisors = activeAdvisors(studentId);
        if (advisors.size() != 1) throw new AdminAssignmentService.Conflict();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO advising_requests (id, student_id, advisor_id, request_type,
                                              title, description, status)
                VALUES (?, ?, ?, 'ACADEMIC_GUIDANCE', ?, ?, 'PENDING')
                """, id, studentId.toString(), advisors.get(0), title, description);
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'New advising request', 'A student requested advising support.', FALSE)
                """, UUID.randomUUID().toString(), advisors.get(0));
        return Map.of("id", id, "status", "PENDING", "advisorId", advisors.get(0));
    }

    private List<String> activeAdvisors(UUID studentId) {
        return jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, (result, row) -> result.getString(1), studentId.toString());
    }
}
