package vn.edu.fpt.faaps.student;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.admin.AdvisingConversationService;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/student/advising-requests")
public class StudentAdvisingConversationController {
    private final AdvisingConversationService conversations;

    public StudentAdvisingConversationController(AdvisingConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    public List<AdvisingConversationService.Request> list(@AuthenticationPrincipal AuthSession student) {
        return conversations.list(student.id(), false);
    }

    @GetMapping("/{id}")
    public AdvisingConversationService.Detail detail(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return conversations.detail(student.id(), false, id); }

    @PostMapping("/{id}/messages")
    public AdvisingConversationService.Detail reply(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id, @Valid @RequestBody MessageInput input) {
        return conversations.addMessage(student.id(), false, id, input.content(), false);
    }

    @PatchMapping("/{id}/cancel")
    public AdvisingConversationService.Detail cancel(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) {
        return conversations.changeStatus(student.id(), false, id, "CANCELLED", null);
    }

    public record MessageInput(@NotBlank @Size(max = 10000) String content) {}
}
