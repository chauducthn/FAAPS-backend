package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public AdminUserService.Page list(@RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID majorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return users.list(role, status, search, majorId, page, size, sort);
    }

    @GetMapping("/{id}")
    public AdminUserService.Detail detail(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PatchMapping("/{id}")
    public AdminUserService.Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateUser request) {
        return users.update(id, request.fullName(), request.phone(), request.avatarUrl());
    }

    @PatchMapping("/{id}/status")
    public AdminUserService.Detail status(@PathVariable UUID id, @Valid @RequestBody ChangeStatus request,
            @AuthenticationPrincipal AuthSession actor) {
        users.changeStatus(List.of(id), request.status(), actor.id());
        return users.detail(id);
    }

    @PostMapping("/status")
    public Map<String, Integer> bulkStatus(@Valid @RequestBody BulkStatus request,
            @AuthenticationPrincipal AuthSession actor) {
        return Map.of("updated", users.changeStatus(request.userIds(), request.status(), actor.id()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(AdminUserService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
    }

    @ExceptionHandler({AdminUserService.Conflict.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "User update conflicts with current data"));
    }

    public record UpdateUser(@NotBlank @Size(max = 255) String fullName,
            @Size(max = 20) String phone, @Size(max = 500) String avatarUrl) {
    }

    public record ChangeStatus(@NotBlank String status) {
    }

    public record BulkStatus(@NotEmpty List<UUID> userIds, @NotBlank String status) {
    }
}
