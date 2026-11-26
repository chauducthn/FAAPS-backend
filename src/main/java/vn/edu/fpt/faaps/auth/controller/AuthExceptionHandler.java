package vn.edu.fpt.faaps.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.auth.exception.AuthUnavailableException;
import vn.edu.fpt.faaps.auth.service.AuthRefreshTokenService;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 429 ? "RATE_LIMITED" : "INVALID_REQUEST";
        String reason = exception.getReason() != null ? exception.getReason() : "Request rejected";
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of(code, reason));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials() {
        return err(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
    }

    @ExceptionHandler(AuthRefreshTokenService.InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> invalidRefreshToken() {
        return err(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid, expired, or revoked");
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> disabledAccount() {
        return err(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Account is not active");
    }

    @ExceptionHandler(AuthUnavailableException.class)
    public ResponseEntity<ApiError> unavailable() {
        return err(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_STORAGE_UNAVAILABLE", "Account storage is not configured");
    }

    private static ResponseEntity<ApiError> err(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }
}
