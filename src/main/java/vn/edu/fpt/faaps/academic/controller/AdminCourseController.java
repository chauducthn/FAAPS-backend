package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.ImportErrorWorkbook;

@RestController
@RequestMapping("/api/admin/courses")
public class AdminCourseController {
    private static final String CODE_PART = "(?:[\\p{Lu}\\d][\\p{Lu}]{2}\\d{2}[\\dx][\\p{Ll}]?\\*?|"
            + "[\\p{Lu}\\d][\\p{L}]{1,5}\\d{0,2}(?:[_-][\\p{L}0-9.*]+)*(?:\\*\\.?\\d+(?:\\.\\d+)?)?\\*?)";
    private static final String CODE_REGEX = "^" + CODE_PART + "(?:/" + CODE_PART + ")*$";
    private static final String CODE_MSG = "Invalid course code format (e.g. CSD201, SSL101c, IoT_GRA_ELE, NWC203c/NWC204)";
    private static final String LINK_REGEX = "^$|^https?://\\S+$";
    private static final String LINK_MSG = "Enter a full link starting with http:// or https://";

    private final AdminCourseService courses;

    public AdminCourseController(AdminCourseService courses) {
        this.courses = courses;
    }

    @GetMapping
    public AdminCourseService.Page list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer credits, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "code,asc") String sort) {
        return courses.list(search, status, categoryId, credits, page, size, sort);
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
        var rel = courses.addRelation(id, request.kind(), request.groupNo(),
                request.otherCourseId(), request.minimumScore());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Course relation created", rel));
    }

    @PutMapping("/{id}/relations/{relationId}")
    public ApiResponse<AdminCourseService.CourseRelation> updateRelation(@PathVariable UUID id,
            @PathVariable UUID relationId, @Valid @RequestBody UpdateRelationRequest request) {
        var updated = courses.updateRelation(id, relationId, request.groupNo(), request.minimumScore());
        return ApiResponse.success("Course relation updated", updated);
    }

    @DeleteMapping("/{id}/relations/{relationId}")
    public ApiResponse<AdminCourseService.CourseRelation> deleteRelation(
            @PathVariable UUID id, @PathVariable UUID relationId) {
        return ApiResponse.success("Course relation deleted", courses.deleteRelation(id, relationId));
    }

    @PostMapping
    public ResponseEntity<AdminCourseService.CourseDetail> create(@Valid @RequestBody CreateCourseRequest request) {
        var created = courses.create(request.code(), request.name(), request.credits(), request.categoryId(),
                request.description(), request.objectives(), request.learningOutcomes(),
                request.materialLink(), request.status(), request.prerequisites());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public AdminCourseService.CourseDetail update(
            @PathVariable UUID id, @Valid @RequestBody UpdateCourseRequest request) {
        return courses.update(id, request.code(), request.name(), request.credits(), request.categoryId(),
                request.description(), request.objectives(), request.learningOutcomes(),
                request.materialLink(), true, request.status(), request.prerequisites());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        courses.delete(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Course deleted successfully"));
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@Valid @RequestBody BulkDeleteRequest request) {
        int deleted = courses.bulkDelete(request.ids());
        return ResponseEntity.ok(Map.of(
                "success", true, "deletedCount", deleted, "message", "Deleted " + deleted + " course(s)"));
    }

    @PostMapping("/bulk-status")
    public ResponseEntity<Map<String, Object>> bulkStatus(@Valid @RequestBody BulkStatusRequest request) {
        int updated = courses.bulkUpdateStatus(request.ids(), request.status());
        return ResponseEntity.ok(Map.of(
                "success", true, "updatedCount", updated, "message", "Updated status for " + updated + " course(s)"));
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminCourseService.ImportPreviewResult previewXlsx(@RequestPart("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        return courses.previewXlsx(file.getInputStream());
    }
    @PostMapping(value = "/import/errors.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> importErrors(@RequestBody ImportErrorRequest request) {
        if (request.items() == null || request.items().stream().noneMatch(item -> !item.errors().isEmpty())) {
            throw new IllegalArgumentException("Import has no validation errors");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=course-import-errors.xlsx")
                .body(ImportErrorWorkbook.courses(request.items()));
    }

    @PostMapping("/import/confirm")
    public ResponseEntity<Map<String, Object>> confirmImport(@Valid @RequestBody ConfirmImportRequest request) {
        int count = courses.confirmImport(request.items());
        return ResponseEntity.ok(Map.of(
                "success", true, "importedCount", count, "message", "Successfully imported " + count + " course(s)"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) { return AcademicErrorResponses.validationWithError(e); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) { return AcademicErrorResponses.invalidWithError(e); }

    @ExceptionHandler(AdminCourseService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminCourseService.NotFound e) { return AcademicErrorResponses.statusWithError(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage()); }

    @ExceptionHandler(AdminCourseService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminCourseService.Conflict e) { return AcademicErrorResponses.statusWithError(HttpStatus.CONFLICT, "CONFLICT", e.getMessage()); }

    public record CreateCourseRequest(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 255) String name, @NotNull @Min(0) @Max(30) Integer credits,
            @NotNull UUID categoryId, String description, String objectives, String learningOutcomes,
            @Size(max = 500, message = "Material link must be at most 500 characters")
            @Pattern(regexp = LINK_REGEX, message = LINK_MSG) String materialLink,
            String status, List<AdminCourseService.PrerequisiteInput> prerequisites) {
        public CreateCourseRequest(String code, String name, Integer credits, UUID categoryId, String description,
                String objectives, String learningOutcomes, String status,
                List<AdminCourseService.PrerequisiteInput> prerequisites) {
            this(code, name, credits, categoryId, description, objectives, learningOutcomes, null, status, prerequisites);
        }
    }

    public record UpdateCourseRequest(
            @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            String name, @Min(0) @Max(30) Integer credits, UUID categoryId,
            String description, String objectives, String learningOutcomes,
            @Size(max = 500, message = "Material link must be at most 500 characters")
            @Pattern(regexp = LINK_REGEX, message = LINK_MSG) String materialLink,
            String status, List<AdminCourseService.PrerequisiteInput> prerequisites) {
        public UpdateCourseRequest(String code, String name, Integer credits, UUID categoryId, String description,
                String objectives, String learningOutcomes, String status,
                List<AdminCourseService.PrerequisiteInput> prerequisites) {
            this(code, name, credits, categoryId, description, objectives, learningOutcomes,
                    AdminCourseService.KEEP_EXISTING_MATERIAL_LINK, status, prerequisites);
        }
    }

    public record BulkDeleteRequest(@NotEmpty List<UUID> ids) {}
    public record BulkStatusRequest(@NotEmpty List<UUID> ids, @NotBlank String status) {}
    public record ConfirmImportRequest(@NotEmpty List<AdminCourseService.ImportConfirmItem> items) {}
    public record ImportErrorRequest(List<AdminCourseService.ImportRowItem> items) {}
    public record AddRelationRequest(@NotBlank String kind, @NotNull @Min(1) @Max(20) Integer groupNo,
            @NotNull UUID otherCourseId, @DecimalMin("0.00") @DecimalMax("10.00") BigDecimal minimumScore) {}
    public record UpdateRelationRequest(@Min(1) @Max(20) Integer groupNo,
            @DecimalMin("0.00") @DecimalMax("10.00") BigDecimal minimumScore) {}
    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> success(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
    }
}
