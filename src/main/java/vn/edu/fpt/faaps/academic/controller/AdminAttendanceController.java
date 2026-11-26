package vn.edu.fpt.faaps.academic.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportHistoryItem;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportPreview;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceRule;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.CourseAttendanceOverride;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping({"/api/admin/attendance", "/admin/attendance"})
public class AdminAttendanceController {
    private final AdminAttendanceService attendance;

    public AdminAttendanceController(AdminAttendanceService attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/config/default")
    public AttendanceRule getDefaultRule() {
        return attendance.getDefaultRule();
    }

    @PutMapping("/config/default")
    public AttendanceRule saveDefaultRule(@RequestBody AttendanceRule rule) {
        return attendance.saveDefaultRule(rule);
    }

    @GetMapping("/config")
    public List<CourseAttendanceOverride> listOverrides() {
        return attendance.listOverrides();
    }

    @PutMapping("/config/{courseId}")
    public CourseAttendanceOverride saveOverride(
            @PathVariable UUID courseId,
            @RequestBody AttendanceRule rule) {
        return attendance.saveOverride(courseId, rule);
    }

    @DeleteMapping("/config/{courseId}")
    public ResponseEntity<Map<String, Object>> removeOverride(@PathVariable UUID courseId) {
        attendance.removeOverride(courseId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Course attendance override removed"));
    }

    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttendanceImportPreview previewImport(@RequestPart("file") MultipartFile file) throws IOException {
        return attendance.previewImport(file);
    }

    @PostMapping("/imports/{id}/confirm")
    public AttendanceImportHistoryItem confirmImport(@PathVariable UUID id) {
        return attendance.confirmImport(id);
    }

    @GetMapping("/imports")
    public List<AttendanceImportHistoryItem> listImports() {
        return attendance.listImports();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> statusException(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiError.of(code, exception.getReason()));
    }
}
