package vn.edu.fpt.faaps.advising.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/advisor/advising-requests", "/advisor/advising-requests", "/api/advisor/requests", "/advisor/requests"})
public class AdvisorAdvisingController {
    private final AdvisingConversationService conversations;

    public AdvisorAdvisingController(AdvisingConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    public List<AdvisingConversationService.Request> list(@AuthenticationPrincipal AuthSession advisor) {
        return conversations.list(advisor.id(), true);
    }

    @GetMapping("/{id}")
    public AdvisingConversationService.Detail detail(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id) { return conversations.detail(advisor.id(), true, id); }

    @PostMapping("/{id}/messages")
    public AdvisingConversationService.Detail reply(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id, @Valid @RequestBody MessageInput input) {
        return conversations.addMessage(advisor.id(), true, id, input.content(), input.internal());
    }

    @PatchMapping("/{id}/status")
    public AdvisingConversationService.Detail patchStatus(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id, @Valid @RequestBody StatusInput input) {
        return conversations.changeStatus(advisor.id(), true, id, input.status(), input.resolutionSummary());
    }

    @PutMapping("/{id}/status")
    public AdvisingConversationService.Detail putStatus(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id, @Valid @RequestBody StatusInput input) {
        return conversations.changeStatus(advisor.id(), true, id, input.status(), input.resolutionSummary());
    }

    public record MessageInput(@NotBlank @Size(max = 10000) String content, boolean internal) {}
    public record StatusInput(@NotBlank String status, String resolutionSummary) {}
}


