package vn.edu.fpt.faaps.student;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/student/advisor")
public class StudentAdvisorController {
    private final StudentAdvisorService advisors;
    public StudentAdvisorController(StudentAdvisorService advisors) { this.advisors = advisors; }

    @GetMapping
    public StudentAdvisorService.Advisor advisor(@AuthenticationPrincipal AuthSession student) {
        return advisors.advisor(student.id());
    }
}
