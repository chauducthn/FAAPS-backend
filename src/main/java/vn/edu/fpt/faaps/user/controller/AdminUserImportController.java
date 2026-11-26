package vn.edu.fpt.faaps.user.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.common.dto.ApiError;
import vn.edu.fpt.faaps.user.service.AdminUserImportService;
import vn.edu.fpt.faaps.user.service.AdminUserImportService.ImportUsersRequest;
import vn.edu.fpt.faaps.user.service.AdminUserImportService.ImportUsersSummary;

@RestController
@RequestMapping({"/api/admin/users", "/admin/users"})
public class AdminUserImportController {

    private final AdminUserImportService importService;

    public AdminUserImportController(AdminUserImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportUsersSummary> importUsers(@RequestBody ImportUsersRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importBatch(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("USER_CONFLICT", "Imported user data conflicts with an existing account or reference."));
    }
}
