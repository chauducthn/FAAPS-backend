package vn.edu.fpt.faaps.dashboard.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping("/api/admin")
public class AdminOverviewController {
    private final JdbcTemplate jdbc;
    private final AdminAppointmentQuery appointmentQuery;

    public AdminOverviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.appointmentQuery = new AdminAppointmentQuery(jdbc);
    }

    @GetMapping("/majors")
    public List<Major> majors() {
        return jdbc.query("SELECT id, code, name FROM majors ORDER BY name, code",
                (result, row) -> new Major(UUID.fromString(result.getString("id")),
                        result.getString("code"), result.getString("name")));
    }

    @GetMapping("/dashboard/accounts")
    public Map<String, Long> accountCounts() {
        return Map.of(
                "students", count("STUDENT"),
                "advisors", count("ADVISOR"),
                "cohorts", jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM curriculum_versions", Long.class));
    }

    @GetMapping("/dashboard/new-students")
    public List<MonthlyCount> newStudents(@RequestParam String startMonth,
            @RequestParam String endMonth, @RequestParam(required = false) UUID majorId) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startMonth + "-01");
            end = LocalDate.parse(endMonth + "-01").plusMonths(1);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("months must use yyyy-MM");
        }
        if (!start.isBefore(end) || start.plusYears(3).isBefore(end)) {
            throw new IllegalArgumentException("month range must be 1-36 months");
        }
        String majorFilter = majorId == null ? "" : " AND s.major_id = ?";
        Object[] parameters = majorId == null
                ? new Object[] {start, end}
                : new Object[] {start, end, majorId.toString()};
        return jdbc.query("""
                SELECT DATE_FORMAT(u.created_at, '%Y-%m') AS month, COUNT(*) AS student_count
                FROM users u JOIN roles r ON r.id = u.role_id
                LEFT JOIN students s ON s.user_id = u.id
                WHERE r.code = 'STUDENT' AND u.created_at >= ? AND u.created_at < ?
                """ + majorFilter + " GROUP BY DATE_FORMAT(u.created_at, '%Y-%m') ORDER BY month",
                (result, row) -> new MonthlyCount(result.getString("month"), result.getLong("student_count")),
                parameters);
    }

    public Page<Appointment> appointments(String status, String search, int page, int size) {
        return appointments(status, search, null, null, null, page, size);
    }

    @GetMapping("/appointments")
    public Page<Appointment> appointments(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID advisorId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return appointmentQuery.list(status, search, advisorId, fromDate, toDate, page, size);
    }

    @GetMapping("/appointments/{id}")
    public Appointment appointment(@PathVariable UUID id) {
        return appointmentQuery.findById(id).orElseThrow(NotFound::new);
    }

    @PatchMapping("/appointments/{id}/status")
    public Appointment updateAppointmentStatus(@PathVariable UUID id, @RequestBody UpdateAppointmentStatus body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String next = body.status() == null ? "" : body.status().strip().toUpperCase();
        if (!List.of("SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW").contains(next)) {
            throw new IllegalArgumentException("status must be SCHEDULED, COMPLETED, CANCELLED, or NO_SHOW");
        }
        String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
        int updated = jdbc.update("""
                UPDATE advising_sessions
                SET status = ?, summary = COALESCE(?, summary), updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, next, note, id.toString());
        if (updated == 0) {
            throw new NotFound();
        }
        return appointmentQuery.findById(id).orElseThrow(NotFound::new);
    }

    public record UpdateAppointmentStatus(String status, String note) {}

    @GetMapping("/appointments/summary")
    public Map<String, Long> appointmentSummary() {
        return Map.of(
                "total", jdbc.queryForObject("SELECT COUNT(*) FROM advising_sessions", Long.class),
                "scheduled", statusCount("SCHEDULED"),
                "completed", statusCount("COMPLETED"),
                "cancelled", statusCount("CANCELLED"),
                "noShow", statusCount("NO_SHOW"));
    }

    private long count(String role) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.code = ?
                """, Long.class, role);
    }

    private long statusCount(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM advising_sessions WHERE status = ?", Long.class, status);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("APPOINTMENT_NOT_FOUND", "Appointment not found"));
    }

    public record MonthlyCount(String month, long students) {}

    public record Major(UUID id, String code, String name) {}

    public record Appointment(String id, String studentId, String studentName,
            String advisorId, String advisorName, LocalDateTime scheduledAt,
            LocalDateTime endedAt, String location, String agenda,
            String decisionNote, String consultationSummary, String status) {
        public Appointment(String id, String studentId, String studentName,
                String advisorId, String advisorName, LocalDateTime scheduledAt,
                LocalDateTime endedAt, String location, String agenda, String status) {
            this(id, studentId, studentName, advisorId, advisorName, scheduledAt, endedAt,
                    location, agenda, null, null, status);
        }
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {}

    private static class NotFound extends RuntimeException {}
}
