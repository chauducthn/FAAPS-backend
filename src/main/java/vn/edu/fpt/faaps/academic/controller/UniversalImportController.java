package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.CurriculumImportService;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;

/**
 * Universal Unified Excel Import Controller.
 * Provides a single entry point for uploading Excel workbooks (.xlsx, .xls)
 * across academic domains (Transcripts, Courses, Curriculums, Attendance) without breaking
 * existing endpoints.
 */
@RestController
@RequestMapping("/api/imports")
public class UniversalImportController {
    private final TranscriptImportService transcriptImports;
    private final UniversalImportDispatcher dispatcher;

    public UniversalImportController(
            TranscriptImportService transcriptImports,
            AdminCourseService courseService,
            CurriculumImportService curriculumImports) {
        this(transcriptImports, courseService, curriculumImports, null);
    }

    @Autowired
    public UniversalImportController(
            TranscriptImportService transcriptImports,
            AdminCourseService courseService,
            CurriculumImportService curriculumImports,
            AdminAttendanceService attendanceService) {
        this.transcriptImports = transcriptImports;
        this.dispatcher = new UniversalImportDispatcher(
                transcriptImports, courseService, curriculumImports, attendanceService);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(
            @AuthenticationPrincipal AuthSession session,
            @RequestPart("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "targetId", required = false) UUID targetId) throws IOException {
        return dispatcher.preview(session, file, type, targetId);
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(
            @AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody UniversalConfirmRequest request) {
        return dispatcher.confirm(session, request);
    }

    @GetMapping(value = "/transcripts/{id}/errors.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> transcriptErrors(
            @AuthenticationPrincipal AuthSession session,
            @PathVariable UUID id) {
        dispatcher.authorize(session, "TRANSCRIPT");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=transcript-import-errors.xlsx")
                .body(transcriptImports.errorWorkbook(session.id(), id));
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
            List<CurriculumImportService.RowItem> curriculumItems) {
    }
}
