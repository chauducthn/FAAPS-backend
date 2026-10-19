package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/majors")
public class AdminMajorController {
    private final AdminMajorService majors;

    public AdminMajorController(AdminMajorService majors) {
        this.majors = majors;
    }

    @GetMapping("/paged")
    public AdminMajorService.Page list(
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return majors.list(cohort, status, search, page, size, sort);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        AdminMajorService.MajorItem item = majors.detail(id);
        return Map.of("data", item, "success", true, "message", "Success");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateMajorRequest request) {
        AdminMajorService.MajorItem created = majors.create(
                request.code(), request.name(), request.description(),
                request.totalCredits() != null ? request.totalCredits() : 140,
                request.durationYears() != null ? request.durationYears() : new BigDecimal("4.0"),
                request.status(), request.cohort());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", created, "success", true, "message", "Major created"));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateMajorRequest request) {
        AdminMajorService.MajorItem updated = majors.update(
                id, request.code(), request.name(), request.description(),
                request.totalCredits(), request.durationYears(), request.status());
        return Map.of("data", updated, "success", true, "message", "Major updated");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        majors.delete(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Major deleted successfully"));
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@Valid @RequestBody BulkDeleteRequest request) {
        int deleted = majors.bulkDelete(request.ids());
        return ResponseEntity.ok(Map.of("success", true, "deletedCount", deleted, "message", "Deleted " + deleted + " major(s)"));
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

    @ExceptionHandler(AdminMajorService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "code", "NOT_FOUND",
                "message", "Major not found",
                "error", "Major not found"
        ));
    }

    @ExceptionHandler(AdminMajorService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminMajorService.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false,
                "code", "CONFLICT",
                "message", exception.getMessage(),
                "error", exception.getMessage()
        ));
    }

    public record CreateMajorRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 255) String name,
            String description,
            @Positive Integer totalCredits,
            BigDecimal durationYears,
            String status,
            String cohort) {
    }

    public record UpdateMajorRequest(
            String code,
            String name,
            String description,
            Integer totalCredits,
            BigDecimal durationYears,
            String status) {
    }

    public record BulkDeleteRequest(@NotEmpty List<UUID> ids) {
    }
}
