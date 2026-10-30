package vn.edu.fpt.faaps.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import vn.edu.fpt.faaps.common.dto.ApiError;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;
import vn.edu.fpt.faaps.auth.service.AuthService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.auth.exception.AuthUnavailableException;
import vn.edu.fpt.faaps.auth.dto.AdminLoginRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final SecurityContextRepository securityContextRepository;
    private final AuthRefreshTokenService refreshTokens;

    public AuthController(AuthService authService, SecurityContextRepository securityContextRepository,
            AuthRefreshTokenService refreshTokens) {
        this.authService = authService;
        this.securityContextRepository = securityContextRepository;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/admin/login")
    public AuthSession adminLogin(@Valid @RequestBody AdminLoginRequest credentials,
            HttpServletRequest request, HttpServletResponse response) {
        return startSession(authService.loginAdmin(credentials.email(), credentials.password()), request, response);
    }

    /** Email + password sign-in for Admin and for students who registered with a password. */
    @PostMapping("/login")
    public AuthSession login(@Valid @RequestBody AdminLoginRequest credentials,
            HttpServletRequest request, HttpServletResponse response) {
        return startSession(authService.login(credentials.email(), credentials.password()), request, response);
    }

    private AuthSession startSession(AuthSession session, HttpServletRequest request, HttpServletResponse response) {
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
        return session;
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthSession> refresh(
            @CookieValue(name = AuthRefreshTokenService.COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            UUID userId = refreshTokens.userIdFor(refreshToken);
            AuthSession session = authService.sessionFor(userId);
            String rotatedToken = refreshTokens.rotate(refreshToken, userId, request);
            if (request.getSession(false) == null) {
                request.getSession(true);
            } else {
                request.changeSessionId();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authService.authentication(session));
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            refreshTokens.writeCookie(response, rotatedToken);
            response.setHeader("Cache-Control", "no-store");
            return ResponseEntity.ok(session);
        } catch (AuthRefreshTokenService.InvalidRefreshTokenException | AuthenticationException exception) {
            refreshTokens.revoke(refreshToken);
            refreshTokens.clearCookie(response);
            throw new AuthRefreshTokenService.InvalidRefreshTokenException();
        }
    }

    @GetMapping("/me")
    public AuthSession me(@AuthenticationPrincipal AuthSession session) {
        return session;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("INVALID_CREDENTIALS", "Invalid credentials"));
    }

    @ExceptionHandler(AuthRefreshTokenService.InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> invalidRefreshToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("INVALID_REFRESH_TOKEN", "Refresh token is invalid, expired, or revoked"));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> disabledAccount() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("ACCOUNT_INACTIVE", "Account is not active"));
    }

    @ExceptionHandler(AuthUnavailableException.class)
    public ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("ACCOUNT_STORAGE_UNAVAILABLE", "Account storage is not configured"));
    }

}
