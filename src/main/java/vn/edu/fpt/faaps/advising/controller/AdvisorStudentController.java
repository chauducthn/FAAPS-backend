package vn.edu.fpt.faaps.advising.controller;

import vn.edu.fpt.faaps.advising.service.AdvisorStudentService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/advisor/students")
public class AdvisorStudentController {
    private final AdvisorStudentService students;
    private final StudentAcademicService academics;
    public AdvisorStudentController(AdvisorStudentService students, StudentAcademicService academics) {
        this.students = students;
        this.academics = academics;
    }

    @GetMapping
    public AdvisorStudentService.Page list(@AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return students.list(advisor.id(), search, page, size);
    }

    @GetMapping("/{studentId}")
    public Detail detail(@AuthenticationPrincipal AuthSession advisor, @PathVariable UUID studentId) {
        if (!students.owns(advisor.id(), studentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        return new Detail(academics.profile(studentId), academics.progress(studentId), academics.transcript(studentId));
    }

    public record Detail(StudentAcademicService.Profile profile, StudentAcademicService.Progress progress,
            List<StudentAcademicService.TranscriptRow> transcript) {}
}


