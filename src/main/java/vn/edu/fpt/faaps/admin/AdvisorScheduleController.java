package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.ApiError;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/advisors/{advisorId}/schedule")
public class AdvisorScheduleController {
    private final JdbcTemplate jdbc;

    public AdvisorScheduleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Schedule> get(@PathVariable UUID advisorId) {
        return jdbc.query("""
                SELECT schedule.work_days, advisor.assignment_start_date
                FROM advisors advisor
                LEFT JOIN advisor_work_schedules schedule ON schedule.advisor_id = advisor.user_id
                WHERE advisor.user_id=?
                """, (result, row) -> new Schedule(advisorId,
                result.getString("work_days") == null || result.getString("work_days").isBlank() ? List.of() :
                        java.util.Arrays.stream(result.getString("work_days").split(","))
                                .map(Integer::parseInt).toList(),
                result.getDate("assignment_start_date") == null ? null :
                        result.getDate("assignment_start_date").toLocalDate()),
                advisorId.toString()).stream().findFirst().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    @Transactional
    public ResponseEntity<Schedule> update(@PathVariable UUID advisorId,
            @Valid @RequestBody UpdateSchedule request, @AuthenticationPrincipal AuthSession actor) {
        if (request.workDays().size() > 7 || request.workDays().stream().anyMatch(day -> day == null || day < 1 || day > 7)
                || request.workDays().stream().distinct().count() != request.workDays().size()) {
            throw new IllegalArgumentException("workDays must contain unique ISO weekdays 1 through 7");
        }
        String days = request.workDays().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        if (jdbc.query("SELECT user_id FROM advisors WHERE user_id=? FOR UPDATE",
                (result, row) -> result.getString(1), advisorId.toString()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        jdbc.update("""
                UPDATE advisors SET assignment_start_date=?, updated_at=CURRENT_TIMESTAMP
                WHERE user_id=?
                """, request.assignmentStartDate(), advisorId.toString());
        Integer scheduleExists = jdbc.queryForObject("SELECT COUNT(*) FROM advisor_work_schedules WHERE advisor_id=?",
                Integer.class, advisorId.toString());
        if (scheduleExists != null && scheduleExists > 0) {
            jdbc.update("""
                    UPDATE advisor_work_schedules SET work_days=?, updated_at=CURRENT_TIMESTAMP
                    WHERE advisor_id=?
                    """, days, advisorId.toString());
        } else {
            jdbc.update("""
                    INSERT INTO advisor_work_schedules (advisor_id, work_days, created_at, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, advisorId.toString(), days);
        }
        return get(advisorId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_SCHEDULE", exception.getMessage()));
    }

    public record UpdateSchedule(@NotEmpty List<Integer> workDays,
            @NotNull LocalDate assignmentStartDate) {}

    public record Schedule(UUID advisorId, List<Integer> workDays, LocalDate assignmentStartDate) {}
}
