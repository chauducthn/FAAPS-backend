package vn.edu.fpt.faaps.controller;

import vn.edu.fpt.faaps.service.AdminCourseService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/courses")
public class AdminCourseController {
    private final AdminCourseService courses;

    public AdminCourseController(AdminCourseService courses) {
        this.courses = courses;
    }

    @GetMapping
    public AdminCourseService.Page list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code,asc") String sort) {
        return courses.list(search, status, categoryId, page, size, sort);
    }

    @GetMapping("/{id}")
    public AdminCourseService.CourseDetail detail(@PathVariable UUID id) {
        return courses.detail(id);
    }

    @GetMapping("/{id}/relations")
    public ApiResponse<List<AdminCourseService.CourseRelation>> relations(@PathVariable UUID id) {
        return ApiResponse.success("Course relations loaded", courses.relations(id));
    }

    @PostMapping("/{id}/relations")
    public ResponseEntity<ApiResponse<AdminCourseService.CourseRelation>> addRelation(
            @PathVariable UUID id, @Valid @RequestBody AddRelationRequest request) {
        AdminCourseService.CourseRelation relation = courses.addRelation(id, request.kind(),
                request.groupNo(), request.otherCourseId(), request.minimumScore());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Course relation created", relation));
    }

    @PutMapping("/{id}/relations/{relationId}")
    public ApiResponse<AdminCourseService.CourseRelation> updateRelation(
            @PathVariable UUID id, @PathVariable UUID relationId,
            @Valid @RequestBody UpdateRelationRequest request) {
        return ApiResponse.success("Course relation updated",
                courses.updateRelation(id, relationId, request.groupNo(), request.minimumScore()));
    }

    @DeleteMapping("/{id}/relations/{relationId}")
    public ApiResponse<AdminCourseService.CourseRelation> deleteRelation(
            @PathVariable UUID id, @PathVariable UUID relationId) {
        return ApiResponse.success("Course relation deleted", courses.deleteRelation(id, relationId));
    }

    @PostMapping
    public ResponseEntity<AdminCourseService.CourseDetail> create(@Valid @RequestBody CreateCourseRequest request) {
        AdminCourseService.CourseDetail created = courses.create(
                request.code(), request.name(), request.credits(), request.categoryId(),
                request.description(), request.objectives(), request.learningOutcomes(),
                request.status(), request.prerequisites());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public AdminCourseService.CourseDetail update(@PathVariable UUID id, @RequestBody UpdateCourseRequest request) {
        return courses.update(
                id, request.code(), request.name(), request.credits(), request.categoryId(),
                request.description(), request.objectives(), request.learningOutcomes(),
                request.status(), request.prerequisites());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        courses.delete(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Course deleted successfully"));
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@Valid @RequestBody BulkDeleteRequest request) {
        int deleted = courses.bulkDelete(request.ids());
        return ResponseEntity.ok(Map.of("success", true, "deletedCount", deleted, "message", "Deleted " + deleted + " course(s)"));
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminCourseService.ImportPreviewResult previewXlsx(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        return courses.previewXlsx(file.getInputStream());
    }

    @PostMapping("/import/confirm")
    public ResponseEntity<Map<String, Object>> confirmImport(@Valid @RequestBody ConfirmImportRequest request) {
        int count = courses.confirmImport(request.items());
        return ResponseEntity.ok(Map.of("success", true, "importedCount", count, "message", "Successfully imported " + count + " course(s)"));
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

    @ExceptionHandler(AdminCourseService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminCourseService.NotFound exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "code", "NOT_FOUND",
                "message", exception.getMessage(),
                "error", exception.getMessage()
        ));
    }

    @ExceptionHandler(AdminCourseService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminCourseService.Conflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false,
                "code", "CONFLICT",
                "message", exception.getMessage(),
                "error", exception.getMessage()
        ));
    }

    public record CreateCourseRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 255) String name,
            @NotNull @Positive Integer credits,
            @NotNull UUID categoryId,
            String description,
            String objectives,
            String learningOutcomes,
            String status,
            List<AdminCourseService.PrerequisiteInput> prerequisites) {
    }

    public record UpdateCourseRequest(
            String code,
            String name,
            Integer credits,
            UUID categoryId,
            String description,
            String objectives,
            String learningOutcomes,
            String status,
            List<AdminCourseService.PrerequisiteInput> prerequisites) {
    }

    public record BulkDeleteRequest(@NotEmpty List<UUID> ids) {
    }

    public record ConfirmImportRequest(@NotEmpty List<AdminCourseService.ImportConfirmItem> items) {
    }

    public record AddRelationRequest(
            @NotBlank String kind,
            @NotNull @Min(1) @Max(20) Integer groupNo,
            @NotNull UUID otherCourseId,
            @DecimalMin("0.00") @DecimalMax("10.00") BigDecimal minimumScore) {
    }

    public record UpdateRelationRequest(
            @Min(1) @Max(20) Integer groupNo,
            @DecimalMin("0.00") @DecimalMax("10.00") BigDecimal minimumScore) {
    }

    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> success(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
    }
}


