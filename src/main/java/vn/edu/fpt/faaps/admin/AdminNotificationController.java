package vn.edu.fpt.faaps.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {
    private final JdbcTemplate jdbc;

    public AdminNotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthSession session,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page or size is invalid");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND (? = FALSE OR is_read = FALSE)",
                Long.class, session.id().toString(), unreadOnly);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT n.id, n.notification_type, n.title, n.message, n.action_url, n.is_read,
                       n.related_student_id, st.student_code, st.curriculum_version_id AS cohort_id,
                       n.resolved_at, n.created_at
                FROM notifications n LEFT JOIN students st ON st.user_id=n.related_student_id
                WHERE n.recipient_user_id = ? AND (? = FALSE OR n.is_read = FALSE)
                ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?
                """, (result, row) -> {
                    java.util.HashMap<String, Object> item = new java.util.HashMap<>();
                    item.put("id", result.getString("id"));
                    item.put("type", result.getString("notification_type"));
                    item.put("title", result.getString("title"));
                    item.put("message", result.getString("message"));
                    item.put("actionUrl", result.getString("action_url"));
                    item.put("relatedStudentId", result.getString("related_student_id"));
                    item.put("studentCode", result.getString("student_code"));
                    item.put("cohortId", result.getString("cohort_id"));
                    item.put("resolvedAt", result.getTimestamp("resolved_at") == null ? null
                            : result.getTimestamp("resolved_at").toInstant());
                    item.put("isRead", result.getBoolean("is_read"));
                    item.put("createdAt", result.getTimestamp("created_at").toInstant());
                    return item;
                }, session.id().toString(), unreadOnly, size, page * size);
        return Map.of("items", items, "page", page, "size", size, "totalElements", total,
                "totalPages", total == 0 ? 0 : (total + size - 1) / size);
    }

    @PatchMapping("/{id}/read")
    public Map<String, Boolean> markRead(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        int updated = jdbc.update("""
                UPDATE notifications SET is_read = TRUE, read_at = CURRENT_TIMESTAMP
                WHERE id = ? AND recipient_user_id = ? AND is_read = FALSE
                """, id.toString(), session.id().toString());
        return Map.of("updated", updated > 0);
    }

    @PatchMapping("/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthSession session) {
        int updated = jdbc.update("UPDATE notifications SET is_read=TRUE, read_at=CURRENT_TIMESTAMP WHERE recipient_user_id=? AND is_read=FALSE",
                session.id().toString());
        return Map.of("updated", updated);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success", false,
                "code", "INVALID_REQUEST", "message", exception.getMessage()));
    }
}
