package vn.edu.fpt.faaps.academic.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

final class AcademicErrorResponses {
    private AcademicErrorResponses() {
    }

    static ResponseEntity<Map<String, Object>> validationWithError(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = extractFieldErrors(exception);
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", "Validation failed",
                "fieldErrors", fieldErrors,
                "error", "Validation failed"));
    }

    static ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", "Validation failed",
                "fieldErrors", extractFieldErrors(exception)));
    }

    static ResponseEntity<Map<String, Object>> invalidWithError(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", message,
                "fieldErrors", Map.of("error", message),
                "error", message));
    }

    static ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", exception.getMessage()));
    }

    static ResponseEntity<Map<String, Object>> statusWithError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "code", code,
                "message", message,
                "error", message));
    }

    static ResponseEntity<Map<String, Object>> status(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "code", code,
                "message", message));
    }

    private static Map<String, String> extractFieldErrors(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return fieldErrors;
    }
}
