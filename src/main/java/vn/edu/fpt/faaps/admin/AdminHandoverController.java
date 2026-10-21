package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.ApiError;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/handovers")
public class AdminHandoverController {
    private final JdbcTemplate jdbc;

    public AdminHandoverController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/current")
    public CurrentAdmin current() {
        return jdbc.queryForObject("""
                SELECT u.id, u.full_name FROM admin_control ac
                JOIN users u ON u.id = ac.current_admin_id WHERE ac.control_id = 1
                """, (result, row) -> new CurrentAdmin(
                UUID.fromString(result.getString("id")), result.getString("full_name")));
    }

    @GetMapping
    public List<Handover> list() {
        return jdbc.query("""
                SELECT h.id, h.from_admin_id, former.full_name AS former_name,
                       h.to_admin_id, successor.full_name AS successor_name,
                       h.handover_date, h.notes, h.recorded_by_user_id, h.created_at
                FROM admin_handovers h
                JOIN users former ON former.id=h.from_admin_id
                JOIN users successor ON successor.id=h.to_admin_id
                ORDER BY h.handover_date DESC, h.created_at DESC
                """, (result, row) -> new Handover(
                UUID.fromString(result.getString("id")), UUID.fromString(result.getString("from_admin_id")),
                result.getString("former_name"), UUID.fromString(result.getString("to_admin_id")),
                result.getString("successor_name"), result.getDate("handover_date").toLocalDate(),
                result.getString("notes"), UUID.fromString(result.getString("recorded_by_user_id")),
                result.getTimestamp("created_at").toLocalDateTime()));
    }

    @PostMapping
    @Transactional
    public Handover create(@Valid @RequestBody CreateHandover request,
            @AuthenticationPrincipal AuthSession actor) {
        if (request.fromAdminId().equals(request.toAdminId())) {
            throw new IllegalArgumentException("Handover requires two different admins");
        }
        if (request.handoverDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Handover date cannot be in the future");
        }
        String currentAdmin = jdbc.queryForObject(
                "SELECT current_admin_id FROM admin_control WHERE control_id = 1 FOR UPDATE", String.class);
        if (currentAdmin == null || !currentAdmin.equals(request.fromAdminId().toString())
                || !currentAdmin.equals(actor.id().toString())) {
            throw new IllegalArgumentException("Only the current admin can hand over their own role");
        }
        LocalDate latestDate = jdbc.query("SELECT handover_date FROM admin_handovers ORDER BY handover_date DESC LIMIT 1",
                (result, row) -> result.getDate(1).toLocalDate()).stream().findFirst().orElse(null);
        if (latestDate != null && request.handoverDate().isBefore(latestDate)) {
            throw new IllegalArgumentException("Handover date cannot precede the latest handover");
        }
        for (UUID adminId : List.of(request.fromAdminId(), request.toAdminId())) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id
                    WHERE u.id=? AND r.code='ADMIN' AND (?=FALSE OR u.status='ACTIVE')
                    """, Integer.class, adminId.toString(), adminId.equals(request.toAdminId()));
            if (count == null || count == 0) throw new IllegalArgumentException("Both accounts must be admins and the incoming admin must be active");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admin_handovers (id,from_admin_id,to_admin_id,handover_date,notes,recorded_by_user_id)
                VALUES (?,?,?,?,?,?)
                """, id.toString(), request.fromAdminId().toString(), request.toAdminId().toString(),
                request.handoverDate(), request.notes(), actor.id().toString());
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message,
                                           action_url, related_student_id, is_read)
                SELECT UUID(), ?, 'ADVISOR_REQUEST', 'Advisor assignment requested',
                       'A student needs an advisor assignment.', '/admin/assignments',
                       n.related_student_id, FALSE
                FROM notifications n
                WHERE n.recipient_user_id = ? AND n.notification_type = 'ADVISOR_REQUEST'
                  AND n.resolved_at IS NULL AND n.related_student_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM notifications successor
                      WHERE successor.recipient_user_id = ? AND successor.related_student_id = n.related_student_id
                        AND successor.notification_type = 'ADVISOR_REQUEST' AND successor.resolved_at IS NULL)
                GROUP BY n.related_student_id
                """, request.toAdminId().toString(), request.fromAdminId().toString(),
                request.toAdminId().toString());
        jdbc.update("""
                UPDATE notifications SET resolved_at = CURRENT_TIMESTAMP
                WHERE recipient_user_id = ? AND notification_type = 'ADVISOR_REQUEST' AND resolved_at IS NULL
                """, request.fromAdminId().toString());
        jdbc.update("UPDATE admin_control SET current_admin_id = ?, updated_at = CURRENT_TIMESTAMP WHERE control_id = 1",
                request.toAdminId().toString());
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_HANDOVER", exception.getMessage()));
    }

    public record CreateHandover(@NotNull UUID fromAdminId, @NotNull UUID toAdminId,
            @NotNull LocalDate handoverDate, String notes) {}

    public record CurrentAdmin(UUID id, String fullName) {}

    public record Handover(UUID id, UUID fromAdminId, String fromAdminName, UUID toAdminId,
            String toAdminName, LocalDate handoverDate, String notes, UUID recordedByUserId,
            java.time.LocalDateTime createdAt) {}
}
