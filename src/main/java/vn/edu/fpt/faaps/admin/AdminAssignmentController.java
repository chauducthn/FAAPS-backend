package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assignments")
public class AdminAssignmentController {
    private final AdminAssignmentService assignments;

    public AdminAssignmentController(AdminAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/advisors")
    public List<AdminAssignmentService.Advisor> advisors() {
        return assignments.advisors();
    }

    @GetMapping("/cohorts")
    public List<AdminAssignmentService.Cohort> cohorts() {
        return assignments.cohorts();
    }

    @GetMapping("/students")
    public List<AdminAssignmentService.Student> students(@RequestParam String cohort,
            @RequestParam(defaultValue = "false") boolean unassignedOnly) {
        return assignments.students(cohort, unassignedOnly);
    }

    @GetMapping("/advisors/{advisorId}")
    public AdminAssignmentService.AdvisorDetail advisor(@PathVariable UUID advisorId) {
        return assignments.advisor(advisorId);
    }

    @GetMapping("/students/{studentId}/history")
    public List<AdminAssignmentService.History> history(@PathVariable UUID studentId) {
        return assignments.history(studentId);
    }

    @PostMapping("/assign")
    public Map<String, Integer> assign(@Valid @RequestBody AssignRequest request) {
        return Map.of("assigned", assignments.assign(request.studentIds(), request.advisorId()));
    }

    @PostMapping("/auto-assign/preview")
    public AdminAssignmentService.Preview preview(@Valid @RequestBody AutoAssignRequest request) {
        return assignments.preview(request.cohort(), request.advisorIds());
    }

    @PostMapping("/auto-assign/confirm")
    public AdminAssignmentService.Result confirm(@Valid @RequestBody AutoAssignRequest request) {
        return assignments.confirm(request.cohort(), request.advisorIds());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(AdminAssignmentService.Conflict.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Assignment data changed; reload and retry"));
    }

    public record AssignRequest(@NotEmpty List<UUID> studentIds, @NotNull UUID advisorId) {
    }

    public record AutoAssignRequest(@NotNull String cohort, @NotEmpty List<UUID> advisorIds) {
    }
}
