package vn.edu.fpt.faaps.academic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.context.SecurityContextRepository;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.academic.service.StudentRegistrationService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;
import vn.edu.fpt.faaps.auth.service.AuthService;

class StudentRegistrationControllerTest {
    private StudentRegistrationService registration;
    private StudentOnboardingService onboarding;
    private AuthService authService;
    private SecurityContextRepository securityContextRepository;
    private AuthRefreshTokenService refreshTokens;
    private StudentRegistrationController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        registration = mock(StudentRegistrationService.class);
        onboarding = mock(StudentOnboardingService.class);
        authService = mock(AuthService.class);
        securityContextRepository = mock(SecurityContextRepository.class);
        refreshTokens = mock(AuthRefreshTokenService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);

        when(request.getSession(any(Boolean.class))).thenReturn(session);
        controller = new StudentRegistrationController(registration, onboarding, authService,
                securityContextRepository, refreshTokens);
    }

    @Test
    void registersWithoutCurriculumVersion() {
        UUID userId = UUID.randomUUID();
        when(registration.register(any(), any(), any(), any(), any(), isNull(), any())).thenReturn(userId);
        when(authService.sessionFor(userId)).thenReturn(new AuthSession(userId, "student@fpt.edu.vn", "Student", "STUDENT", "ACTIVE"));

        StudentRegistrationController.RegisterRequest body = new StudentRegistrationController.RegisterRequest(
                "Nguyen Van A", "student@fpt.edu.vn", "Secret@123", LocalDate.of(2005, 1, 1),
                "SE190001", null, "0901234567");

        assertNull(body.parsedCurriculumVersionId());
        ResponseEntity<AuthSession> res = controller.register(body, request, response);
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        verify(registration).register("Nguyen Van A", "student@fpt.edu.vn", "Secret@123",
                LocalDate.of(2005, 1, 1), "SE190001", null, "0901234567");
    }

    @Test
    void registersWithEmptyStringCurriculumVersion() {
        UUID userId = UUID.randomUUID();
        when(registration.register(any(), any(), any(), any(), any(), isNull(), any())).thenReturn(userId);
        when(authService.sessionFor(userId)).thenReturn(new AuthSession(userId, "student@fpt.edu.vn", "Student", "STUDENT", "ACTIVE"));

        StudentRegistrationController.RegisterRequest body = new StudentRegistrationController.RegisterRequest(
                "Nguyen Van A", "student@fpt.edu.vn", "Secret@123", LocalDate.of(2005, 1, 1),
                "SE190001", "   ", "0901234567");

        assertNull(body.parsedCurriculumVersionId());
        ResponseEntity<AuthSession> res = controller.register(body, request, response);
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        verify(registration).register("Nguyen Van A", "student@fpt.edu.vn", "Secret@123",
                LocalDate.of(2005, 1, 1), "SE190001", null, "0901234567");
    }

    @Test
    void registersWithValidCurriculumVersion() {
        UUID userId = UUID.randomUUID();
        UUID curId = UUID.randomUUID();
        when(registration.register(any(), any(), any(), any(), any(), eq(curId), any())).thenReturn(userId);
        when(authService.sessionFor(userId)).thenReturn(new AuthSession(userId, "student@fpt.edu.vn", "Student", "STUDENT", "ACTIVE"));

        StudentRegistrationController.RegisterRequest body = new StudentRegistrationController.RegisterRequest(
                "Nguyen Van A", "student@fpt.edu.vn", "Secret@123", LocalDate.of(2005, 1, 1),
                "SE190001", curId.toString(), "0901234567");

        assertEquals(curId, body.parsedCurriculumVersionId());
        ResponseEntity<AuthSession> res = controller.register(body, request, response);
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        verify(registration).register("Nguyen Van A", "student@fpt.edu.vn", "Secret@123",
                LocalDate.of(2005, 1, 1), "SE190001", curId, "0901234567");
    }
}
