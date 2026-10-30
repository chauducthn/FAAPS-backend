package vn.edu.fpt.faaps.academic.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.academic.service.StudentRegistrationService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;
import vn.edu.fpt.faaps.auth.service.AuthService;
import vn.edu.fpt.faaps.common.dto.ApiError;

/** Public endpoints for the registration page: no account is needed to look at the curriculums or to register. */
@RestController
@RequestMapping("/api/auth")
public class StudentRegistrationController {
    private final StudentRegistrationService registration;
    private final StudentOnboardingService onboarding;
    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final AuthRefreshTokenService refreshTokens;

    public StudentRegistrationController(StudentRegistrationService registration, StudentOnboardingService onboarding,
            AuthService authService, SecurityContextRepository securityContextRepository,
            AuthRefreshTokenService refreshTokens) {
        this.registration = registration;
        this.onboarding = onboarding;
        this.authService = authService;
        this.securityContextRepository = securityContextRepository;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/register/curriculums")
    public List<StudentOnboardingService.CurriculumChoice> curriculums() {
        return onboarding.curriculumChoices();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthSession> register(@Valid @RequestBody RegisterRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        UUID userId = registration.register(body.fullName(), body.email(), body.password(), body.dateOfBirth(),
                body.studentCode(), body.curriculumVersionId(), body.phone());
        AuthSession session = authService.sessionFor(userId);
        if (request.getSession(false) == null) {
            request.getSession(true);
        } else {
            request.changeSessionId();
        }
        refreshTokens.issueCookie(session.id(), request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authService.authentication(session));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /** Spring hides the reason of a ResponseStatusException by default; the registration form needs to show it. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> failed(ResponseStatusException exception) {
        String code = switch (exception.getStatusCode().value()) {
            case 409 -> "CONFLICT";
            case 404 -> "NOT_FOUND";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of(code, exception.getReason()));
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(max = 255) String email,
            @NotBlank @Size(max = 72) String password,
            @NotNull LocalDate dateOfBirth,
            @NotBlank @Size(max = 20) String studentCode,
            @NotNull UUID curriculumVersionId,
            @Size(max = 20) String phone) {
    }
}
