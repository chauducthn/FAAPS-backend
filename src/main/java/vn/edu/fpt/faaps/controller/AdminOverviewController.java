package vn.edu.fpt.faaps.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.dto.ApiError;

@RestController
@RequestMapping("/api/admin")
public class AdminOverviewController {
    private final JdbcTemplate jdbc;

    public AdminOverviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    @GetMapping("/appointments")
    public Page<Appointment> appointments(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !List.of("SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "NO_SHOW").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        if (status != null) {
            where.append(" AND session.status = ?");
            values.add(status);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(student.full_name) LIKE ? OR LOWER(advisor.full_name) LIKE ? OR LOWER(session.agenda) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            values.add(term);
            values.add(term);
            values.add(term);
        }
        String from = " FROM advising_sessions session JOIN users student ON student.id = session.student_id JOIN users advisor ON advisor.id = session.advisor_id";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, values.toArray());
        values.add(size);
        values.add(page * size);
        List<Appointment> items = jdbc.query("""
                SELECT session.id, session.student_id, student.full_name AS student_name,
                       session.advisor_id, advisor.full_name AS advisor_name,
                       session.scheduled_at, session.ended_at, session.location,
                       session.agenda, session.status
                """ + from + where + " ORDER BY session.scheduled_at DESC, session.id DESC LIMIT ? OFFSET ?",
                (result, row) -> appointment(result), values.toArray());
        return new Page<>(items, page, size, total, (total + size - 1) / size);
    }

    @GetMapping("/appointments/{id}")
    public Appointment appointment(@PathVariable UUID id) {
        return jdbc.query("""
                SELECT session.id, session.student_id, student.full_name AS student_name,
                       session.advisor_id, advisor.full_name AS advisor_name,
                       session.scheduled_at, session.ended_at, session.location,
                       session.agenda, session.status
                FROM advising_sessions session
                JOIN users student ON student.id = session.student_id
                JOIN users advisor ON advisor.id = session.advisor_id
                WHERE session.id = ?
                """, (result, row) -> appointment(result), id.toString()).stream().findFirst()
                .orElseThrow(NotFound::new);
    }

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

    private Appointment appointment(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Appointment(result.getString("id"), result.getString("student_id"),
                result.getString("student_name"), result.getString("advisor_id"),
                result.getString("advisor_name"), result.getTimestamp("scheduled_at") == null
                        ? null : result.getTimestamp("scheduled_at").toLocalDateTime(),
                result.getTimestamp("ended_at") == null
                        ? null : result.getTimestamp("ended_at").toLocalDateTime(),
                result.getString("location"), result.getString("agenda"), result.getString("status"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("APPOINTMENT_NOT_FOUND", "Appointment not found"));
    }

    public record MonthlyCount(String month, long students) {
    }

    public record Major(UUID id, String code, String name) {
    }

    public record Appointment(String id, String studentId, String studentName,
            String advisorId, String advisorName, java.time.LocalDateTime scheduledAt,
            java.time.LocalDateTime endedAt, String location, String agenda, String status) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {
    }

    private static class NotFound extends RuntimeException {
    }
}

