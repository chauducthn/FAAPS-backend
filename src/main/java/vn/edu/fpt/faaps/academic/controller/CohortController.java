package vn.edu.fpt.faaps.academic.controller;

import vn.edu.fpt.faaps.academic.service.AdminCurriculumService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/admin/cohorts")
public class CohortController {
    private final AdminCurriculumService curriculums;

    public CohortController(AdminCurriculumService curriculums) {
        this.curriculums = curriculums;
    }

    @GetMapping
    public AdminCurriculumService.Page list(
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isCurrent,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return curriculums.list(majorId, status, isCurrent, search, page, size);
    }

    @GetMapping("/{id}")
    public CurriculumController.ApiResponse<AdminCurriculumService.CurriculumItem> detail(@PathVariable UUID id) {
        return CurriculumController.ApiResponse.success("Cohort loaded", curriculums.detail(id));
    }

    @PostMapping
    public ResponseEntity<CurriculumController.ApiResponse<AdminCurriculumService.CurriculumItem>> create(
            @Valid @RequestBody CreateCohortRequest request) {
        AdminCurriculumService.CurriculumItem created = curriculums.create(request.toInput());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CurriculumController.ApiResponse.success("Cohort created", created));
    }

    @PutMapping("/{id}")
    public CurriculumController.ApiResponse<AdminCurriculumService.CurriculumItem> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateCohortRequest request) {
        return CurriculumController.ApiResponse.success("Cohort updated", curriculums.update(id, request.toInput()));
    }

    @DeleteMapping("/{id}")
    public CurriculumController.ApiResponse<Void> delete(@PathVariable UUID id) {
        curriculums.delete(id);
        return CurriculumController.ApiResponse.success("Cohort deleted", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new java.util.HashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "success", false, "code", "VALIDATION_ERROR", "message", "Validation failed", "fieldErrors", fieldErrors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false, "code", "VALIDATION_ERROR", "message", exception.getMessage()));
    }

    @ExceptionHandler(AdminCurriculumService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminCurriculumService.NotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false, "code", "NOT_FOUND", "message", exception.getMessage()));
    }

    @ExceptionHandler(AdminCurriculumService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminCurriculumService.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false, "code", "CONFLICT", "message", exception.getMessage()));
    }

    public record CreateCohortRequest(
            @NotNull UUID majorId,
            @NotBlank @Size(max = 50) String version,
            @NotBlank @Size(max = 255) String name,
            @NotNull UUID effectiveFromSemesterId,
            UUID effectiveToSemesterId,
            String description,
            Boolean isCurrent,
            String status) {
        AdminCurriculumService.CurriculumInput toInput() {
            return new AdminCurriculumService.CurriculumInput(majorId, version, name,
                    effectiveFromSemesterId, effectiveToSemesterId, description, isCurrent, status);
        }
    }

    public record UpdateCohortRequest(
            @NotNull UUID majorId,
            @NotBlank @Size(max = 50) String version,
            @NotBlank @Size(max = 255) String name,
            @NotNull UUID effectiveFromSemesterId,
            UUID effectiveToSemesterId,
            String description,
            @NotNull Boolean isCurrent,
            @NotBlank String status) {
        AdminCurriculumService.CurriculumInput toInput() {
            return new AdminCurriculumService.CurriculumInput(majorId, version, name,
                    effectiveFromSemesterId, effectiveToSemesterId, description, isCurrent, status);
        }
    }
}


