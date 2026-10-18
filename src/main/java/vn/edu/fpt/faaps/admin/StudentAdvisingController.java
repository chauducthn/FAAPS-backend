package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/student")
public class StudentAdvisingController {
    private final AssignmentRequestService requests;

    public StudentAdvisingController(AssignmentRequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/advisor-assignment-requests")
    public Map<String, Object> requestAdvisor(@AuthenticationPrincipal AuthSession session) {
        return requests.requestAdvisor(session.id());
    }

    @GetMapping("/advisor-assignment")
    public Map<String, Object> assignmentStatus(@AuthenticationPrincipal AuthSession session) {
        return requests.assignmentStatus(session.id());
    }

    @PostMapping("/advising-requests")
    public Map<String, Object> requestAdvising(@AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody AdvisingRequest request) {
        return requests.requestAdvising(session.id(), request.title(), request.description());
    }

    public record AdvisingRequest(@NotBlank @Size(max = 255) String title,
            @NotBlank String description) {}
}
