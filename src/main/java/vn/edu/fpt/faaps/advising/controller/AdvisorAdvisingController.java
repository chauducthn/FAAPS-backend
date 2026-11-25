package vn.edu.fpt.faaps.advising.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
    public List<AdvisingConversationService.Request> list(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return conversations.list(advisor.id(), true, status, search);
    }

    @GetMapping("/paged")
    public AdvisingConversationService.Page listPaged(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversations.listPaged(advisor.id(), true, status, search, page, size);
    }

    @GetMapping("/{id}")
    public AdvisingConversationService.Detail detail(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id) { return conversations.detail(advisor.id(), true, id); }

    @PostMapping("/{id}/messages")
    public AdvisingConversationService.Detail reply(@AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id, @Valid @RequestBody MessageInput input) {
        return conversations.addMessage(advisor.id(), true, id, input.content(), input.internal());
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdvisingConversationService.Attachment uploadAttachment(
            @AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", required = false) String type) {
        conversations.detail(advisor.id(), true, id);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment file is required");
        }
        String attId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment";
        String url = "/api/advisor/advising-requests/" + id + "/attachments/" + attId;
        return new AdvisingConversationService.Attachment(attId, fileName, url, file.getSize(), LocalDateTime.now());
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


