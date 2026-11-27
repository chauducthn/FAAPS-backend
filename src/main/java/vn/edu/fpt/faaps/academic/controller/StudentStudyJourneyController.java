package vn.edu.fpt.faaps.academic.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.StudentStudyJourneyService;
import vn.edu.fpt.faaps.academic.service.StudentStudyJourneyService.StudyJourneyGoal;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/student/study-journey", "/student/study-journey"})
public class StudentStudyJourneyController {
    private final StudentStudyJourneyService service;

    public StudentStudyJourneyController(StudentStudyJourneyService service) {
        this.service = service;
    }

    @GetMapping
    public StudyJourneyGoal get(@AuthenticationPrincipal AuthSession student) {
        return service.get(student.id());
    }

    @PutMapping
    public StudyJourneyGoal save(@AuthenticationPrincipal AuthSession student,
            @RequestBody StudyJourneyGoal input) {
        return service.save(student.id(), input);
    }
}
