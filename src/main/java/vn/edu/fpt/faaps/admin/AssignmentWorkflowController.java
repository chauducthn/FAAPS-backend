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

@RestController
@RequestMapping("/api/admin/assignments")
public class AssignmentWorkflowController {
    private final AssignmentWorkflowService workflow;

    public AssignmentWorkflowController(AssignmentWorkflowService workflow) {
        this.workflow = workflow;
    }

    @GetMapping("/unassigned")
    public List<Map<String, Object>> unassigned(@RequestParam(required = false) UUID cohortId) {
        return workflow.unassigned(cohortId);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return workflow.summary();
    }

    @GetMapping("/advisors/by-cohort")
    public List<Map<String, Object>> advisorsWithCohorts() {
        return workflow.advisorsWithCohorts();
    }

    @PatchMapping("/advisors/{advisorId}/limit")
    public Map<String, Object> updateLimit(@PathVariable UUID advisorId, @Valid @RequestBody LimitRequest request) {
        return workflow.updateLimit(advisorId, request.maxAdvisees());
    }

    @PostMapping("/cohorts/{cohortId}/assign")
    public Map<String, Object> assignCohort(@PathVariable UUID cohortId, @Valid @RequestBody CohortRequest request) {
        return workflow.assignCohort(cohortId, request.advisorId());
    }

    @PostMapping("/cohorts/{cohortId}/preview")
    public Map<String, Object> previewCohort(@PathVariable UUID cohortId,
            @Valid @RequestBody CohortRequest request) {
        return workflow.previewCohortAssignment(cohortId, request.advisorId());
    }

    public record LimitRequest(@Min(1) int maxAdvisees) {}
    public record CohortRequest(@NotNull UUID advisorId) {}
}
