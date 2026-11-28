package vn.edu.fpt.faaps.advising.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService.AtRiskCounts;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService.AtRiskStudent;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService.AtRiskStudentResponse;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping({"/api/advisor/at-risk-students", "/advisor/at-risk-students"})
public class AdvisorAtRiskController {
    private final AdvisorAtRiskService atRiskService;

    public AdvisorAtRiskController(AdvisorAtRiskService atRiskService) {
        this.atRiskService = atRiskService;
    }

    @GetMapping
    public AtRiskStudentResponse getAtRiskStudents(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) UUID semesterId) {
        return atRiskService.list(advisor.id(), riskLevel, reason, semesterId, page, size);
    }

    @GetMapping("/counts")
    public AtRiskCounts getCounts(@AuthenticationPrincipal AuthSession advisor) {
        return atRiskService.counts(advisor.id());
    }

    @PostMapping("/{studentId}/acknowledge")
    public AtRiskStudent acknowledge(
            @AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID studentId,
            @RequestBody(required = false) AcknowledgeRequest body) {
        return atRiskService.acknowledge(advisor.id(), studentId, body != null ? body.note() : null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        String code = ex.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(ex.getStatusCode()).body(ApiError.of(code, ex.getReason()));
    }

    public record AcknowledgeRequest(String note) {}
}
