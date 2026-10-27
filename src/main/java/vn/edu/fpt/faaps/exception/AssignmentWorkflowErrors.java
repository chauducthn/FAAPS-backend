package vn.edu.fpt.faaps.exception;

import vn.edu.fpt.faaps.controller.AssignmentWorkflowController;
import vn.edu.fpt.faaps.controller.CohortAdvisorController;
import vn.edu.fpt.faaps.controller.StudentAdvisingController;
import vn.edu.fpt.faaps.service.AdminAssignmentService;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice(assignableTypes = {
        AssignmentWorkflowController.class, StudentAdvisingController.class,
        CohortAdvisorController.class
})
public class AssignmentWorkflowErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("Request body is invalid");
        return ResponseEntity.badRequest().body(Map.of("success", false, "code", "INVALID_REQUEST", "message", message));
    }

    @ExceptionHandler(AdminAssignmentService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminAssignmentService.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "code", "ASSIGNMENT_CONFLICT", "message", "Assignment state changed or the request is not allowed"));
    }

    @ExceptionHandler(AdminAssignmentService.AssignmentError.class)
    public ResponseEntity<Map<String, Object>> assignmentError(AdminAssignmentService.AssignmentError exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("success", false,
                "code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler(AdminAssignmentService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminAssignmentService.NotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false,
                "code", "RESOURCE_NOT_FOUND", "message", exception.getMessage()));
    }
}


