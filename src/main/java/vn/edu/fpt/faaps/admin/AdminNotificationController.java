package vn.edu.fpt.faaps.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {
    private final JdbcTemplate jdbc;

    public AdminNotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthSession session,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return jdbc.query("""
                SELECT id, notification_type, title, message, action_url, is_read,
                       related_student_id, resolved_at, created_at
                FROM notifications WHERE user_id = ? AND (? = FALSE OR is_read = FALSE)
                ORDER BY created_at DESC LIMIT 100
                """, (result, row) -> {
                    java.util.HashMap<String, Object> item = new java.util.HashMap<>();
                    item.put("id", result.getString("id"));
                    item.put("type", result.getString("notification_type"));
                    item.put("title", result.getString("title"));
                    item.put("message", result.getString("message"));
                    item.put("actionUrl", result.getString("action_url"));
                    item.put("relatedStudentId", result.getString("related_student_id"));
                    item.put("resolvedAt", result.getTimestamp("resolved_at") == null ? null
                            : result.getTimestamp("resolved_at").toInstant());
                    item.put("isRead", result.getBoolean("is_read"));
                    item.put("createdAt", result.getTimestamp("created_at").toInstant());
                    return item;
                }, session.id().toString(), unreadOnly);
    }

    @PatchMapping("/{id}/read")
    public Map<String, Boolean> markRead(@AuthenticationPrincipal AuthSession session, @PathVariable UUID id) {
        int updated = jdbc.update("""
                UPDATE notifications SET is_read = TRUE, read_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ? AND is_read = FALSE
                """, id.toString(), session.id().toString());
        return Map.of("updated", updated > 0);
    }
}
