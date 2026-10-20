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
        return list().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_HANDOVER", exception.getMessage()));
    }

    public record CreateHandover(@NotNull UUID fromAdminId, @NotNull UUID toAdminId,
            @NotNull LocalDate handoverDate, String notes) {}

    public record Handover(UUID id, UUID fromAdminId, String fromAdminName, UUID toAdminId,
            String toAdminName, LocalDate handoverDate, String notes, UUID recordedByUserId,
            java.time.LocalDateTime createdAt) {}
}
