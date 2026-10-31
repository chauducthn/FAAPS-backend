package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.CurriculumImportService;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

/**
 * Universal Unified Excel Import Controller.
 * Provides a single entry point for uploading Excel workbooks (.xlsx, .xls)
 * across academic domains (Transcripts, Courses, Curriculums) without breaking
 * existing endpoints.
 */
@RestController
@RequestMapping("/api/imports")
public class UniversalImportController {
    private final TranscriptImportService transcriptImports;
    private final AdminCourseService courseService;
    private final CurriculumImportService curriculumImports;

    public UniversalImportController(
            TranscriptImportService transcriptImports,
            AdminCourseService courseService,
            CurriculumImportService curriculumImports) {
        this.transcriptImports = transcriptImports;
        this.courseService = courseService;
        this.curriculumImports = curriculumImports;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(
            @AuthenticationPrincipal AuthSession session,
            @RequestPart("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "targetId", required = false) UUID targetId) throws IOException {

        validateExcelFile(file);

        String normalizedType = type == null ? "" : type.trim().toUpperCase();

        // Role-based security check: Students may ONLY import their own transcripts
        if (session != null && "STUDENT".equalsIgnoreCase(session.role()) && !"TRANSCRIPT".equals(normalizedType)) {
            throw new AccessDeniedException("Students are only authorized to import academic transcripts");
        }

        return switch (normalizedType) {
            case "TRANSCRIPT" -> {
                if (session == null) {
                    throw new AccessDeniedException("Authentication required for transcript import");
                }
                yield ResponseEntity.ok(transcriptImports.preview(session.id(), file));
            }
            case "COURSE" -> ResponseEntity.ok(courseService.previewXlsx(file.getInputStream()));
            case "CURRICULUM" -> {
                if (targetId == null) {
                    throw new IllegalArgumentException("targetId (curriculumId) is required for CURRICULUM import");
                }
                yield ResponseEntity.ok(curriculumImports.preview(file.getInputStream()));
            }
            default -> throw new IllegalArgumentException("Unsupported import type: " + type + ". Supported: TRANSCRIPT, COURSE, CURRICULUM");
        };
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(
            @AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody UniversalConfirmRequest request) {

        String normalizedType = request.type() == null ? "" : request.type().trim().toUpperCase();

        if (session != null && "STUDENT".equalsIgnoreCase(session.role()) && !"TRANSCRIPT".equals(normalizedType)) {
            throw new AccessDeniedException("Students are only authorized to confirm academic transcripts");
        }

        return switch (normalizedType) {
            case "TRANSCRIPT" -> {
                if (session == null) {
                    throw new AccessDeniedException("Authentication required for transcript confirmation");
                }
                UUID id = request.importId() != null ? request.importId() : request.targetId();
                if (id == null) throw new IllegalArgumentException("importId is required for transcript confirmation");
                yield ResponseEntity.ok(transcriptImports.confirm(session.id(), id));
            }
            case "COURSE" -> {
                if (request.courseItems() == null || request.courseItems().isEmpty()) {
                    throw new IllegalArgumentException("courseItems is required for course import confirmation");
                }
                int count = courseService.confirmImport(request.courseItems());
                yield ResponseEntity.ok(Map.of("success", true, "importedCount", count, "message", "Successfully imported " + count + " course(s)"));
            }
            case "CURRICULUM" -> {
                if (request.targetId() == null) {
                    throw new IllegalArgumentException("targetId (curriculumId) is required for curriculum confirmation");
                }
                if (request.curriculumItems() == null || request.curriculumItems().isEmpty()) {
                    throw new IllegalArgumentException("curriculumItems is required for curriculum import confirmation");
                }
                int count = curriculumImports.confirm(request.targetId(), request.curriculumItems());
                yield ResponseEntity.ok(Map.of("success", true, "importedCount", count, "message", "Successfully imported " + count + " curriculum course(s)"));
            }
            default -> throw new IllegalArgumentException("Unsupported import type: " + request.type());
        };
    }

    @GetMapping(value = "/transcripts/{id}/errors.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> transcriptErrors(
            @AuthenticationPrincipal AuthSession session,
            @PathVariable UUID id) {
        if (session == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=transcript-import-errors.xlsx")
                .body(transcriptImports.errorWorkbook(session.id(), id));
    }

    private void validateExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls"))) {
            throw new InvalidFileFormatException("Invalid file format. Please upload an Excel workbook (.xlsx or .xls).");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            boolean isExcelMime = contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    || contentType.equals("application/vnd.ms-excel")
                    || contentType.equals("application/octet-stream");
            if (!isExcelMime) {
                throw new InvalidFileFormatException("Invalid file format. Please upload an Excel workbook (.xlsx or .xls).");
            }
        }
    }

    @ExceptionHandler(InvalidFileFormatException.class)
    public ResponseEntity<ApiError> handleInvalidFormat(InvalidFileFormatException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("INVALID_FILE_FORMAT", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", ex.getMessage()));
    }

    public static class InvalidFileFormatException extends RuntimeException {
        public InvalidFileFormatException(String message) {
            super(message);
        }
    }

    public record UniversalConfirmRequest(
            @NotBlank String type,
            UUID targetId,
            UUID importId,
            List<AdminCourseService.ImportConfirmItem> courseItems,
            List<CurriculumImportService.RowItem> curriculumItems
    ) {}
}
