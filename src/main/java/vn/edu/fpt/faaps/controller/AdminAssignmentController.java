package vn.edu.fpt.faaps.controller;

import vn.edu.fpt.faaps.service.AdminAssignmentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;

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
    public AdminAssignmentService.Page<AdminAssignmentService.Student> students(
            @RequestParam(required = false) UUID cohortId, @RequestParam(required = false) String cohort,
            @RequestParam(defaultValue = "false") boolean unassignedOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search, @RequestParam(required = false) String advisorId,
            @RequestParam(required = false) String status) {
        return assignments.students(cohortId, cohort, unassignedOnly, page, size, search, advisorId, status);
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
    public Map<String, Object> assign(@AuthenticationPrincipal AuthSession session, @Valid @RequestBody AssignRequest request) {
        int count = assignments.assign(request.studentIds(), request.advisorId(),
                Boolean.TRUE.equals(request.acknowledgeAppointmentCancellation()), session.id(), request.notes());
        AdminAssignmentService.Advisor advisor = assignments.advisor(request.advisorId()).advisor();
        int excess = advisor.maxAdvisees() == null ? 0
                : (int) Math.max(0, advisor.activeStudents() - advisor.maxAdvisees());
        return Map.of("assigned", count, "overLimitBy", excess, "warning", excess > 0);
    }

    @PostMapping("/assign/preview")
    public Map<String, Object> previewAssignment(@Valid @RequestBody PreviewAssignRequest request) {
        List<AdminAssignmentService.AppointmentImpact> appointments =
                assignments.appointmentImpacts(request.studentIds(), request.advisorId());
        return Map.of("scheduledAppointments", appointments, "appointmentCount", appointments.size(),
                "warning", !appointments.isEmpty());
    }

    @PostMapping("/unassign/preview")
    public Map<String, Object> previewUnassign(@Valid @RequestBody UnassignPreviewRequest request) {
        List<AdminAssignmentService.AppointmentImpact> appointments =
                assignments.appointmentImpacts(request.studentIds(), null);
        return Map.of("scheduledAppointments", appointments, "appointmentCount", appointments.size(),
                "warning", !appointments.isEmpty());
    }

    @PostMapping("/unassign")
    public Map<String, Integer> unassign(@AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody UnassignRequest request) {
        return Map.of("unassigned", assignments.unassign(request.studentIds(),
                Boolean.TRUE.equals(request.acknowledgeAppointmentCancellation()), session.id(), request.notes()));
    }

    @PostMapping("/auto-assign/preview")
    public AdminAssignmentService.Preview preview(@Valid @RequestBody AutoAssignRequest request) {
        return assignments.preview(request.cohort(), request.advisorIds());
    }

    @PostMapping("/auto-assign/confirm")
    public AdminAssignmentService.Result confirm(@AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody AutoAssignRequest request) {
        return assignments.confirm(request.cohort(), request.advisorIds(), session.id(), request.notes());
    }

    @PostMapping("/auto-assign/cohort/preview")
    public Map<String, Object> previewCohort(@Valid @RequestBody CohortAutoAssignRequest request) {
        return assignments.previewCohort(request.cohortId(), request.advisorIds());
    }

    @PostMapping("/auto-assign/cohort/confirm")
    public Map<String, Object> confirmCohort(@AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody CohortAutoAssignRequest request) {
        return assignments.confirmCohort(request.cohortId(), request.advisorIds(), session.id(), request.notes());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("Request body is invalid");
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "INVALID_REQUEST", "message", message));
    }

    @ExceptionHandler(AdminAssignmentService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminAssignmentService.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("success", false, "code", "ASSIGNMENT_CONFLICT", "message", "Assignment data changed; reload and retry"));
    }

    @ExceptionHandler(AdminAssignmentService.AssignmentError.class)
    public ResponseEntity<Map<String, Object>> assignmentError(AdminAssignmentService.AssignmentError exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("success", false, "code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler(AdminAssignmentService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminAssignmentService.NotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "code", "ADVISOR_NOT_FOUND", "message", exception.getMessage()));
    }

    @ExceptionHandler(AdminAssignmentService.AppointmentWarningRequired.class)
    public ResponseEntity<Map<String, Object>> appointmentWarning(
            AdminAssignmentService.AppointmentWarningRequired exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false, "code", "APPOINTMENT_CANCELLATION_CONFIRMATION_REQUIRED",
                "message", "Future appointments will be cancelled; confirm before reassigning",
                "appointments", exception.appointments(), "confirmationRequired", true));
    }

    public record AssignRequest(@NotEmpty List<UUID> studentIds, @NotNull UUID advisorId,
            Boolean acknowledgeAppointmentCancellation, String notes) {
    }

    public record PreviewAssignRequest(@NotEmpty List<UUID> studentIds, @NotNull UUID advisorId) {}

    public record UnassignPreviewRequest(@NotEmpty List<UUID> studentIds) {}

    public record UnassignRequest(@NotEmpty List<UUID> studentIds,
            Boolean acknowledgeAppointmentCancellation, String notes) {}

    public record AutoAssignRequest(@NotNull String cohort, @NotEmpty List<UUID> advisorIds, String notes) {
    }

    public record CohortAutoAssignRequest(@NotNull UUID cohortId, List<UUID> advisorIds, String notes) {
    }
}


