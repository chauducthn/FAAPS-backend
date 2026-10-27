package vn.edu.fpt.faaps.notification.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long unreadCount(UUID recipientId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_user_id=? AND is_read=FALSE
                """, Long.class, recipientId.toString());
        return count == null ? 0 : count;
    }

    public Page list(UUID recipientId, boolean unreadOnly, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        String unread = unreadOnly ? " AND is_read=FALSE" : "";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id=?" + unread,
                Long.class, recipientId.toString());
        List<Item> items = jdbc.query("""
                SELECT id,recipient_user_id,notification_type,title,message,action_url,related_student_id,
                       resolved_at,is_read,read_at,created_at
                FROM notifications WHERE recipient_user_id=?
                """ + unread + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (rs, row) -> new Item(
                rs.getString("id"), rs.getString("recipient_user_id"), rs.getString("notification_type"),
                rs.getString("title"), rs.getString("message"), rs.getString("action_url"),
                rs.getString("related_student_id"), time(rs, "resolved_at"), rs.getBoolean("is_read"),
                time(rs, "read_at"), time(rs, "created_at")), recipientId.toString(), size, page * size);
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    @Transactional
    public boolean read(UUID recipientId, UUID notificationId) {
        return jdbc.update("""
                UPDATE notifications SET is_read=TRUE,read_at=COALESCE(read_at,CURRENT_TIMESTAMP)
                WHERE id=? AND recipient_user_id=?
                """, notificationId.toString(), recipientId.toString()) == 1;
    }

    @Transactional
    public int readAll(UUID recipientId) {
        return jdbc.update("""
                UPDATE notifications SET is_read=TRUE,read_at=CURRENT_TIMESTAMP
                WHERE recipient_user_id=? AND is_read=FALSE
                """, recipientId.toString());
    }

    private LocalDateTime time(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record Item(String id, String recipientUserId, String notificationType, String title,
            String message, String actionUrl, String relatedStudentId, LocalDateTime resolvedAt,
            boolean isRead, LocalDateTime readAt, LocalDateTime createdAt) {}
    public record Page(List<Item> items, int page, int size, long totalElements, long totalPages) {}
}
