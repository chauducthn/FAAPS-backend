package vn.edu.fpt.faaps.dashboard.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.dashboard.service.AdminDatabaseService;

@RestController
@RequestMapping("/api/admin/database")
public class AdminDatabaseController {
    private final AdminDatabaseService databaseService;

    public AdminDatabaseController(AdminDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@AuthenticationPrincipal AuthSession session) {
        return Map.of("success", true, "data", databaseService.getOverview(session.id()));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(
            @AuthenticationPrincipal AuthSession session,
            @RequestBody(required = false) ResetRequest request) {
        String confirmation = request != null && request.confirmation() != null ? request.confirmation().strip() : "";
        if (!"RESET".equalsIgnoreCase(confirmation)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Type RESET to confirm database reset"));
        }
        String scope = request != null && request.scope() != null && !request.scope().isBlank()
                ? request.scope().strip() : "ALL";
        try {
            AdminDatabaseService.ResetResult result = databaseService.resetScope(session.id(), scope);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Reset complete (" + result.scope() + "): " + result.deletedRows() + " rows cleared.",
                    "data", result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    public record ResetRequest(String confirmation, String scope) {
    }
}
