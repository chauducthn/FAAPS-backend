package vn.edu.fpt.faaps.academic.controller;

import vn.edu.fpt.faaps.academic.service.StudentAcademicService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping({"/api/student", "/student"})
public class StudentAcademicController {
    private final StudentAcademicService academics;

    public StudentAcademicController(StudentAcademicService academics) {
        this.academics = academics;
    }

    @GetMapping("/profile")
    public StudentAcademicService.Profile profile(@AuthenticationPrincipal AuthSession student) {
        return academics.profile(student.id());
    }

    @PutMapping("/profile")
    public StudentAcademicService.Profile updateProfile(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody UpdateProfile request) {
        return academics.updateProfile(student.id(), request.fullName(), request.phone());
    }

    @PutMapping("/profile/curriculum")
    public StudentAcademicService.Profile changeCurriculum(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody ChangeCurriculumRequest request) {
        return academics.changeCurriculum(student.id(), request.curriculumVersionId());
    }

    @GetMapping("/transcript")
    public List<StudentAcademicService.TranscriptRow> transcript(@AuthenticationPrincipal AuthSession student) {
        return academics.transcript(student.id());
    }

    @GetMapping("/progress")
    public StudentAcademicService.Progress progress(@AuthenticationPrincipal AuthSession student) {
        return academics.progress(student.id());
    }

    @GetMapping("/courses")
    public List<StudentAcademicService.Course> courses(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return academics.courses(search, page, size);
    }

    @GetMapping("/courses/paged")
    public StudentAcademicService.CoursePage coursesPaged(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return academics.coursesPaged(search, page, size);
    }

    @GetMapping("/courses/{id}")
    public CourseDetail course(@PathVariable UUID id) {
        return new CourseDetail(academics.course(id), academics.prerequisites(id));
    }

    @GetMapping("/curriculum")
    public StudentAcademicService.Curriculum curriculum(@AuthenticationPrincipal AuthSession student) {
        return academics.curriculum(student.id());
    }

    @GetMapping("/curriculum/courses")
    public List<StudentAcademicService.CurriculumCourse> curriculumCourses(@AuthenticationPrincipal AuthSession student) {
        return academics.curriculumCourses(student.id());
    }

    @GetMapping("/warnings")
    public List<StudentAcademicService.AcademicWarning> warnings(@AuthenticationPrincipal AuthSession student) {
        return academics.warnings(student.id());
    }

    /** Spring hides the reason of a ResponseStatusException by default; client UI needs the specific message. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> failed(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 409 ? "CONFLICT"
                : exception.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of(code, exception.getReason()));
    }

    public record UpdateProfile(@NotBlank @Size(max = 255) String fullName,
            @Size(max = 20) String phone) {}
    public record ChangeCurriculumRequest(@NotNull UUID curriculumVersionId) {}
    public record CourseDetail(StudentAcademicService.Course course,
            List<StudentAcademicService.Prerequisite> prerequisites) {}
}


