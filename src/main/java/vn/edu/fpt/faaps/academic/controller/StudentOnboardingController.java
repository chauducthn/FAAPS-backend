package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping("/api/student/onboarding")
public class StudentOnboardingController {
    private final StudentOnboardingService onboarding;
    private final StudentAcademicService academics;

    public StudentOnboardingController(StudentOnboardingService onboarding, StudentAcademicService academics) {
        this.onboarding = onboarding;
        this.academics = academics;
    }

    @GetMapping("/curriculums")
    public List<StudentOnboardingService.CurriculumChoice> curriculums() {
        return onboarding.curriculumChoices();
    }

    @PutMapping
    public StudentAcademicService.Profile complete(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody CompleteOnboardingRequest request) {
        onboarding.complete(student.id(), request.studentCode(), request.curriculumVersionId(),
                request.dateOfBirth(), request.phone());
        return academics.profile(student.id());
    }

    /** Spring hides the reason of a ResponseStatusException by default; the registration form needs to show it. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> failed(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 409 ? "CONFLICT"
                : exception.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of(code, exception.getReason()));
    }

    public record CompleteOnboardingRequest(
            @NotBlank @Size(max = 20) String studentCode,
            @NotNull UUID curriculumVersionId,
            @NotNull LocalDate dateOfBirth,
            @Size(max = 20) String phone) {
    }
}
