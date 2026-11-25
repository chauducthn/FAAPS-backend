package vn.edu.fpt.faaps.academic.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService;
import vn.edu.fpt.faaps.academic.service.CurriculumImportService;

@RestController
@RequestMapping("/api/admin/curriculums")
public class CurriculumController {
    private final AdminCurriculumService curriculums;
    private final CurriculumImportService imports;

    public CurriculumController(AdminCurriculumService curriculums, CurriculumImportService imports) {
        this.curriculums = curriculums;
        this.imports = imports;
    }

    @GetMapping
    public AdminCurriculumService.Page list(@RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) String status, @RequestParam(required = false) Boolean isCurrent,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return curriculums.list(majorId, status, isCurrent, search, page, size);
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminCurriculumService.CurriculumItem> detail(@PathVariable UUID id) {
        return ApiResponse.success("Curriculum loaded", curriculums.detail(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminCurriculumService.CurriculumItem>> create(
            @Valid @RequestBody CreateCurriculumRequest request) {
        var created = curriculums.create(request.toInput());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Curriculum created", created));
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
        var added = curriculums.addCourses(id, request.courseIds(), request.semesterNumber(), request.isMandatory());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Courses added to curriculum", added));
    }

    @PatchMapping("/{id}/courses/{courseId}")
    public ApiResponse<AdminCurriculumService.CurriculumCourseItem> updateCourse(@PathVariable UUID id,
            @PathVariable UUID courseId, @Valid @RequestBody UpdateCurriculumCourseRequest request) {
        var updated = curriculums.updateCourse(
                id, courseId, request.semesterNumber(), request.isMandatory(), request.groupName());
        return ApiResponse.success("Curriculum course updated", updated);
    }

    @DeleteMapping("/{id}/courses/{courseId}")
    public ApiResponse<Void> removeCourse(@PathVariable UUID id, @PathVariable UUID courseId) {
        curriculums.removeCourse(id, courseId);
        return ApiResponse.success("Course removed from curriculum", null);
    }

    @PostMapping(value = "/{id}/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CurriculumImportService.Preview previewImport(
            @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        curriculums.detail(id);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        return imports.preview(file.getInputStream());
    }

    @PostMapping("/{id}/import/confirm")
    public Map<String, Object> confirmImport(
            @PathVariable UUID id, @RequestBody CurriculumImportService.Preview preview) {
        int count = imports.confirm(id, preview.items());
        return Map.of("importedCount", count, "success", true);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminCurriculumService.CurriculumItem> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return ApiResponse.success("Curriculum status updated", curriculums.updateStatus(id, request.status()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        return AcademicErrorResponses.validation(exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return AcademicErrorResponses.invalid(exception);
    }

    @ExceptionHandler(AdminCurriculumService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminCurriculumService.NotFound exception) {
        return AcademicErrorResponses.status(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AdminCurriculumService.Conflict.class)
    public ResponseEntity<Map<String, Object>> conflict(AdminCurriculumService.Conflict exception) {
        return AcademicErrorResponses.status(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    public record UpdateCurriculumCourseRequest(
            @NotNull @Min(0) @Max(12) Integer semesterNumber, Boolean isMandatory, @Size(max = 100) String groupName) {}

    public record CreateCurriculumRequest(
            @NotNull UUID majorId, @NotBlank @Size(max = 50) String version, @NotBlank @Size(max = 255) String name,
            @NotNull UUID effectiveFromSemesterId, UUID effectiveToSemesterId,
            String description, Boolean isCurrent, String status) {
        AdminCurriculumService.CurriculumInput toInput() {
            return new AdminCurriculumService.CurriculumInput(majorId, version, name,
                    effectiveFromSemesterId, effectiveToSemesterId, description, isCurrent, status);
        }
    }

    public record UpdateCurriculumRequest(
            @NotNull UUID majorId, @NotBlank @Size(max = 50) String version, @NotBlank @Size(max = 255) String name,
            @NotNull UUID effectiveFromSemesterId, UUID effectiveToSemesterId,
            String description, @NotNull Boolean isCurrent, @NotBlank String status) {
        AdminCurriculumService.CurriculumInput toInput() {
            return new AdminCurriculumService.CurriculumInput(majorId, version, name,
                    effectiveFromSemesterId, effectiveToSemesterId, description, isCurrent, status);
        }
    }

    public record AddCourseRequest(
            @JsonProperty("courseIds") @JsonAlias("course_ids")
            @NotEmpty @Size(max = 100) List<@NotNull UUID> courseIds,
            @JsonProperty("semesterNumber") @JsonAlias("semester_number")
            @NotNull @Min(0) @Max(12) Integer semesterNumber,
            @JsonProperty("isMandatory") @JsonAlias("is_mandatory") Boolean isMandatory) {}

    public record UpdateStatusRequest(@NotBlank String status) {}

    public record ApiResponse<T>(boolean success, String message, T data) {
        public static <T> ApiResponse<T> success(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
    }
}
