package vn.edu.fpt.faaps.advising.controller;

import vn.edu.fpt.faaps.advising.service.StudentAdvisorService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;

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


