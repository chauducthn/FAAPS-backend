package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/assignments")
public class AssignmentWorkflowController {
    private final AssignmentWorkflowService workflow;

    public AssignmentWorkflowController(AssignmentWorkflowService workflow) {
        this.workflow = workflow;
    }

    @GetMapping("/unassigned")
    public AssignmentWorkflowService.Page<Map<String, Object>> unassigned(
            @RequestParam(required = false) UUID cohortId, @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean hasPendingRequest,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return workflow.unassigned(cohortId, search, hasPendingRequest, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return workflow.summary();
    }

    @GetMapping("/advisors/by-cohort")
    public AssignmentWorkflowService.Page<Map<String, Object>> advisorsWithCohorts(
            @RequestParam(required = false) UUID cohortId, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return workflow.advisorsWithCohorts(cohortId, search, page, size);
    }

    @PatchMapping("/advisors/{advisorId}/limit")
    public Map<String, Object> updateLimit(@PathVariable UUID advisorId, @Valid @RequestBody LimitRequest request) {
        return workflow.updateLimit(advisorId, request.maxAdvisees());
    }

    @PostMapping("/cohorts/{cohortId}/assign")
    public Map<String, Object> assignCohort(@AuthenticationPrincipal AuthSession session,
            @PathVariable UUID cohortId, @Valid @RequestBody CohortRequest request) {
        return workflow.assignCohort(cohortId, request.advisorId(), session.id(), request.notes());
    }

    @PostMapping("/cohorts/{cohortId}/preview")
    public Map<String, Object> previewCohort(@PathVariable UUID cohortId,
            @Valid @RequestBody CohortRequest request) {
        return workflow.previewCohortAssignment(cohortId, request.advisorId());
    }

    public record LimitRequest(@Min(1) int maxAdvisees) {}
    public record CohortRequest(@NotNull UUID advisorId, String notes) {}
}
