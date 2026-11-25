package vn.edu.fpt.faaps.academic.controller;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.controller.UniversalImportController.InvalidFileFormatException;
import vn.edu.fpt.faaps.academic.controller.UniversalImportController.UniversalConfirmRequest;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.CurriculumImportService;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

final class UniversalImportDispatcher {
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final TranscriptImportService transcriptImports;
    private final AdminCourseService courseService;
    private final CurriculumImportService curriculumImports;
    private final AdminAttendanceService attendanceService;

    UniversalImportDispatcher(TranscriptImportService transcriptImports, AdminCourseService courseService,
            CurriculumImportService curriculumImports, AdminAttendanceService attendanceService) {
        this.transcriptImports = transcriptImports;
        this.courseService = courseService;
        this.curriculumImports = curriculumImports;
        this.attendanceService = attendanceService;
    }

    ResponseEntity<?> preview(AuthSession session, MultipartFile file, String type, UUID targetId)
            throws IOException {
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if ("ATTENDANCE".equals(normalizedType)) {
            validateAttendanceFile(file);
        } else {
            validateExcelFile(file);
        }
        authorize(session, normalizedType);
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
            case "ATTENDANCE" -> {
                if (attendanceService == null) {
                    throw new IllegalStateException("Attendance import service is not configured");
                }
                yield ResponseEntity.ok(attendanceService.previewImport(file));
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported import type: " + type + ". Supported: TRANSCRIPT, COURSE, CURRICULUM, ATTENDANCE");
        };
    }

    ResponseEntity<?> confirm(AuthSession session, UniversalConfirmRequest request) {
        String normalizedType = request.type() == null ? "" : request.type().trim().toUpperCase();
        authorize(session, normalizedType);
        return switch (normalizedType) {
            case "TRANSCRIPT" -> confirmTranscript(session, request);
            case "COURSE" -> confirmCourse(request);
            case "CURRICULUM" -> confirmCurriculum(request);
            case "ATTENDANCE" -> confirmAttendance(request);
            default -> throw new IllegalArgumentException("Unsupported import type: " + request.type());
        };
    }

    void authorize(AuthSession session, String type) {
        if (session == null || ("TRANSCRIPT".equals(type)
                ? !"STUDENT".equalsIgnoreCase(session.role())
                : !"ADMIN".equalsIgnoreCase(session.role()))) {
            throw new AccessDeniedException("Not authorized for this import type");
        }
    }

    private ResponseEntity<?> confirmTranscript(AuthSession session, UniversalConfirmRequest request) {
        if (session == null) {
            throw new AccessDeniedException("Authentication required for transcript confirmation");
        }
        UUID id = request.importId() != null ? request.importId() : request.targetId();
        if (id == null) {
            throw new IllegalArgumentException("importId is required for transcript confirmation");
        }
        return ResponseEntity.ok(transcriptImports.confirm(session.id(), id));
    }

    private ResponseEntity<?> confirmCourse(UniversalConfirmRequest request) {
        if (request.courseItems() == null || request.courseItems().isEmpty()) {
            throw new IllegalArgumentException("courseItems is required for course import confirmation");
        }
        int count = courseService.confirmImport(request.courseItems());
        return ResponseEntity.ok(Map.of("success", true, "importedCount", count,
                "message", "Successfully imported " + count + " course(s)"));
    }

    private ResponseEntity<?> confirmCurriculum(UniversalConfirmRequest request) {
        if (request.targetId() == null) {
            throw new IllegalArgumentException("targetId (curriculumId) is required for curriculum confirmation");
        }
        if (request.curriculumItems() == null || request.curriculumItems().isEmpty()) {
            throw new IllegalArgumentException("curriculumItems is required for curriculum import confirmation");
        }
        int count = curriculumImports.confirm(request.targetId(), request.curriculumItems());
        return ResponseEntity.ok(Map.of("success", true, "importedCount", count,
                "message", "Successfully imported " + count + " curriculum course(s)"));
    }

    private ResponseEntity<?> confirmAttendance(UniversalConfirmRequest request) {
        if (attendanceService == null) {
            throw new IllegalStateException("Attendance import service is not configured");
        }
        UUID id = request.importId() != null ? request.importId() : request.targetId();
        if (id == null) {
            throw new IllegalArgumentException("importId is required for attendance import confirmation");
        }
        return ResponseEntity.ok(attendanceService.confirmImport(id));
    }

    private void validateAttendanceFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls") && !lower.endsWith(".csv")) {
            throw new InvalidFileFormatException(
                    "Invalid file format. Please upload an Excel workbook (.xlsx, .xls) or CSV (.csv).");
        }
    }

    private void validateExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new InvalidFileFormatException(
                    "Invalid file format. Please upload an Excel workbook (.xlsx or .xls).");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            boolean isExcelMime = contentType.equals(XLSX_MIME)
                    || contentType.equals("application/vnd.ms-excel")
                    || contentType.equals("application/octet-stream");
            if (!isExcelMime) {
                throw new InvalidFileFormatException(
                        "Invalid file format. Please upload an Excel workbook (.xlsx or .xls).");
            }
        }
    }
}
