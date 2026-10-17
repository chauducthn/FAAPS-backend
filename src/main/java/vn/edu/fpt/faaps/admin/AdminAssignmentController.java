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
    public Map<String, Object> assign(@Valid @RequestBody AssignRequest request) {
        int count = assignments.assign(request.studentIds(), request.advisorId(),
                request.acknowledgeAppointmentCancellation());
        AdminAssignmentService.Advisor advisor = assignments.advisor(request.advisorId()).advisor();
        int excess = advisor.maxAdvisees() == null ? 0
                : (int) Math.max(0, advisor.activeStudents() - advisor.maxAdvisees());
        return Map.of("assigned", count, "overLimitBy", excess, "warning", excess > 0);
    }

    @PostMapping("/assign/preview")
    public Map<String, Object> previewAssignment(@Valid @RequestBody AssignRequest request) {
        List<AdminAssignmentService.AppointmentImpact> appointments =
                assignments.appointmentImpacts(request.studentIds(), request.advisorId());
        return Map.of("scheduledAppointments", appointments, "appointmentCount", appointments.size(),
                "warning", !appointments.isEmpty());
    }

    @PostMapping("/auto-assign/preview")
    public AdminAssignmentService.Preview preview(@Valid @RequestBody AutoAssignRequest request) {
        return assignments.preview(request.cohort(), request.advisorIds());
    }

    @PostMapping("/auto-assign/confirm")
    public AdminAssignmentService.Result confirm(@Valid @RequestBody AutoAssignRequest request) {
        return assignments.confirm(request.cohort(), request.advisorIds());
    }

    @PostMapping("/auto-assign/cohort/preview")
    public Map<String, Object> previewCohort(@Valid @RequestBody CohortAutoAssignRequest request) {
        return assignments.previewCohort(request.cohortId(), request.advisorIds());
    }

    @PostMapping("/auto-assign/cohort/confirm")
    public Map<String, Object> confirmCohort(@Valid @RequestBody CohortAutoAssignRequest request) {
        return assignments.confirmCohort(request.cohortId(), request.advisorIds());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(AdminAssignmentService.Conflict.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Assignment data changed; reload and retry"));
    }

    @ExceptionHandler(AdminAssignmentService.AppointmentWarningRequired.class)
    public ResponseEntity<Map<String, Object>> appointmentWarning(
            AdminAssignmentService.AppointmentWarningRequired exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Future appointments will be cancelled; confirm before reassigning",
                "appointments", exception.appointments(), "confirmationRequired", true));
    }

    public record AssignRequest(@NotEmpty List<UUID> studentIds, @NotNull UUID advisorId,
            boolean acknowledgeAppointmentCancellation) {
    }

    public record AutoAssignRequest(@NotNull String cohort, @NotEmpty List<UUID> advisorIds) {
    }

    public record CohortAutoAssignRequest(@NotNull UUID cohortId, List<UUID> advisorIds) {
    }
}
