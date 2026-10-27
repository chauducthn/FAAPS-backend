package vn.edu.fpt.faaps.controller;

import vn.edu.fpt.faaps.service.AdminAiReportService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.dto.ApiError;

@RestController
@RequestMapping({"/api/admin/ai-reports", "/admin/ai-reports"})
public class AdminAiReportController {
    private final AdminAiReportService reports;

    public AdminAiReportController(AdminAiReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public AdminAiReportService.Page list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reports.list(status, search, page, size);
    }

    @GetMapping("/{id}")
    public AdminAiReportService.Detail detail(@PathVariable UUID id) {
        return reports.detail(id);
    }

    @PostMapping("/{id}/resolve")
    public AdminAiReportService.Detail resolve(@PathVariable UUID id,
            @Valid @RequestBody ResolveRequest request,
            @AuthenticationPrincipal AuthSession actor) {
        reports.resolve(id, request.status(), request.resolutionNote(), actor.id());
        return reports.detail(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(AdminAiReportService.NotFound.class)
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("REPORT_NOT_FOUND", "Report not found"));
    }

    @ExceptionHandler(AdminAiReportService.Conflict.class)
    public ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("REPORT_ALREADY_CLOSED", "Report is already closed"));
    }

    public record ResolveRequest(@NotBlank String status, @NotBlank String resolutionNote) {
    }
}


