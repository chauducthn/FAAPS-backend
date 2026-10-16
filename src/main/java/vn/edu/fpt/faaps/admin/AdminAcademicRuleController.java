package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/academic-rules")
public class AdminAcademicRuleController {
    private final AdminAcademicRuleService rules;

    public AdminAcademicRuleController(AdminAcademicRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public AdminAcademicRuleService.Page list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String policyType,
            @RequestParam(required = false) UUID curriculumVersionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "priority,desc") String sort) {
        return rules.list(search, status, policyType, curriculumVersionId, page, size, sort);
    }

    @GetMapping("/types")
    public List<String> types() {
        return rules.types();
    }

    @GetMapping("/{id}")
    public AdminAcademicRuleService.RuleSummary detail(@PathVariable UUID id) {
        return rules.detail(id);
    }

    @PostMapping
    public ResponseEntity<AdminAcademicRuleService.RuleSummary> create(@Valid @RequestBody CreateRuleRequest request) {
        AdminAcademicRuleService.RuleSummary created = rules.create(
                request.curriculumVersionId(), request.policyType(),
                request.conditionExpression(), request.restrictionExpression(),
                request.approvalRequired(), request.priority(),
                request.description(), request.displayMessage(), request.isActive());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public AdminAcademicRuleService.RuleSummary update(@PathVariable UUID id, @RequestBody UpdateRuleRequest request) {
        return rules.update(
                id, request.curriculumVersionId(), request.policyType(),
                request.conditionExpression(), request.restrictionExpression(),
                request.approvalRequired(), request.priority(),
                request.description(), request.displayMessage(), request.isActive());
    }

    @PatchMapping("/{id}/status")
    public AdminAcademicRuleService.RuleSummary updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return rules.updateStatus(id, request.isActive());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        rules.delete(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Academic rule deleted successfully"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new java.util.HashMap<>();
        for (org.springframework.validation.FieldError fe : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", "Validation failed",
                "fieldErrors", fieldErrors,
                "error", "Validation failed"
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "code", "VALIDATION_ERROR",
                "message", exception.getMessage(),
                "fieldErrors", Map.of("error", exception.getMessage()),
                "error", exception.getMessage()
        ));
    }

    @ExceptionHandler(AdminAcademicRuleService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "code", "NOT_FOUND",
                "message", "Academic rule not found",
                "error", "Academic rule not found"
        ));
    }

    public record CreateRuleRequest(
            @NotNull UUID curriculumVersionId,
            @NotBlank String policyType,
            String conditionExpression,
            String restrictionExpression,
            Boolean approvalRequired,
            Integer priority,
            String description,
            String displayMessage,
            Boolean isActive) {
    }

    public record UpdateRuleRequest(
            UUID curriculumVersionId,
            String policyType,
            String conditionExpression,
            String restrictionExpression,
            Boolean approvalRequired,
            Integer priority,
            String description,
            String displayMessage,
            Boolean isActive) {
    }

    public record UpdateStatusRequest(boolean isActive) {
    }
}
