package vn.edu.fpt.faaps.common.exception;

import vn.edu.fpt.faaps.common.dto.ApiError;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> responseStatus(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 409 ? "CONFLICT"
                : exception.getStatusCode().value() == 404 ? "NOT_FOUND"
                : exception.getStatusCode().value() == 400 ? "INVALID_REQUEST"
                : exception.getStatusCode().value() == 403 ? "FORBIDDEN"
                : exception.getStatusCode().value() == 401 ? "UNAUTHORIZED"
                : "API_ERROR";
        String message = exception.getReason() != null ? exception.getReason() : exception.getMessage();
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of(code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request body is invalid");
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", "Request parameters or body are invalid"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }
}
