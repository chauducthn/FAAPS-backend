package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@RequestMapping("/api/admin/curriculums")
public class CurriculumController {
    private final AdminCurriculumService curriculums;

    public CurriculumController(AdminCurriculumService curriculums) {
        this.curriculums = curriculums;
    }

    @GetMapping
    public AdminCurriculumService.Page list(
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return curriculums.list(majorId, status, search, page, size);
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminCurriculumService.CurriculumItem> detail(@PathVariable UUID id) {
        return ApiResponse.success("Curriculum loaded", curriculums.detail(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminCurriculumService.CurriculumItem>> create(
            @Valid @RequestBody CreateCurriculumRequest request) {
        AdminCurriculumService.CurriculumItem created = curriculums.create(request.toInput());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Curriculum created", created));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminCurriculumService.CurriculumItem> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateCurriculumRequest request) {
        return ApiResponse.success("Curriculum updated", curriculums.update(id, request.toInput()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        curriculums.delete(id);
        return ApiResponse.success("Curriculum deleted", null);
    }

    @GetMapping("/{id}/courses")
    public ApiResponse<List<AdminCurriculumService.CurriculumCourseItem>> courses(@PathVariable UUID id) {
        return ApiResponse.success("Curriculum courses loaded", curriculums.courses(id));
    }

    @PostMapping("/{id}/courses")
    public ResponseEntity<ApiResponse<List<AdminCurriculumService.CurriculumCourseItem>>> addCourses(
            @PathVariable UUID id, @Valid @RequestBody AddCourseRequest request) {
        List<AdminCurriculumService.CurriculumCourseItem> added = curriculums.addCourses(
                id, request.courseIds(), request.semesterNumber(), request.isMandatory());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Courses added to curriculum", added));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminCurriculumService.CurriculumItem> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return ApiResponse.success("Curriculum status updated", curriculums.updateStatus(id, request.status()));
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

    public record CreateCurriculumRequest(
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

    public record UpdateCurriculumRequest(
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

    public record AddCourseRequest(
            @JsonProperty("courseIds") @JsonAlias("course_ids")
            @NotEmpty @Size(max = 100) List<@NotNull UUID> courseIds,
            @JsonProperty("semesterNumber") @JsonAlias("semester_number")
            @NotNull Integer semesterNumber,
            @JsonProperty("isMandatory") @JsonAlias("is_mandatory")
            Boolean isMandatory) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> success(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
    }
}
