package vn.edu.fpt.faaps.ai.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.ai.service.AdvisorAiCopilotService;
import vn.edu.fpt.faaps.ai.service.AdvisorAiCopilotService.CopilotResult;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/advisor/ai", "/advisor/ai"})
public class AdvisorAiController {
    private final AdvisorAiCopilotService copilot;

    public AdvisorAiController(AdvisorAiCopilotService copilot) {
        this.copilot = copilot;
    }

    @PostMapping("/students/{studentId}/brief")
    public CopilotResult studentBrief(
            @PathVariable UUID studentId,
            @AuthenticationPrincipal AuthSession advisor) {
        return copilot.generateStudentBrief(advisor.id(), studentId);
    }

    @PostMapping("/students/{studentId}/outreach-draft")
    public AdvisorAiCopilotService.OutreachDraft outreachDraft(
            @PathVariable UUID studentId,
            @AuthenticationPrincipal AuthSession advisor) {
        return copilot.draftOutreachEmail(advisor.id(), studentId);
    }

    @PostMapping("/sessions/{sessionId}/draft-summary")
    public CopilotResult draftSummary(
            @PathVariable UUID sessionId,
            @Valid @RequestBody DraftSummaryRequest body,
            @AuthenticationPrincipal AuthSession advisor) {
        return copilot.draftSessionSummary(advisor.id(), sessionId, body.rawNotes());
    }

    public record DraftSummaryRequest(@NotBlank(message = "rawNotes is required") String rawNotes) {}
}
