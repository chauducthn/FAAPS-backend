package vn.edu.fpt.faaps.user.controller;


import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;
import vn.edu.fpt.faaps.user.service.AdminUserService;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkResetPassword;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.EmailCheckItem;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

@RestController
@RequestMapping({"/api/admin/users", "/admin/users"})
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public Page list(@RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) UUID curriculumVersionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return users.list(role, status, search, majorId, curriculumVersionId, page, size, sort);
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PostMapping
    public ResponseEntity<Detail> create(@Valid @RequestBody CreateUserRequest request) {
        Detail created = users.create(new CreateAccount(
                request.role(), request.email(), request.fullName(), request.phone(),
                request.studentCode(), request.advisorCode(), request.curriculumVersionId(),
                request.enrollmentSemesterId(), request.expectedGraduationSemesterId(),
                request.currentSemester(), request.maxAdvisees()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}/status")
    public Detail status(@PathVariable UUID id, @Valid @RequestBody ChangeStatus request,
            @AuthenticationPrincipal AuthSession actor) {
        users.changeStatus(List.of(id), request.status(), actor.id());
        return users.detail(id);
    }

    @PatchMapping("/{id}/role")
    public Detail role(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return users.changeRole(id, new ChangeRole(request.role(), request.email(), request.studentCode(),
                request.advisorCode(), request.curriculumVersionId(), request.enrollmentSemesterId(),
                request.maxAdvisees()));
    }

    @PostMapping("/role")
    public BulkRoleResponse bulkRole(@Valid @RequestBody BulkRoleRequest request) {
        return users.bulkChangeRole(request.role(), request.items());
    }

    @PostMapping("/check-emails")
    public Map<String, Boolean> checkEmails(@Valid @RequestBody EmailCheckRequest request) {
        return users.checkEmailAvailability(request.items());
    }

    @PostMapping("/status")
    public Map<String, Integer> bulkStatus(@Valid @RequestBody BulkStatus request,
            @AuthenticationPrincipal AuthSession actor) {
        return Map.of("updated", users.changeStatus(request.userIds(), request.status(), actor.id()));
    }

    @PostMapping("/{id}/reset-password")
    public PasswordResetResponse resetPassword(@PathVariable UUID id) {
        return users.resetPassword(id);
    }

    @PostMapping("/reset-password")
    public PasswordResetResponse bulkResetPassword(@Valid @RequestBody BulkResetPassword request) {
        return users.bulkResetPassword(request.userIds());
    }

    @DeleteMapping("/{id}")
    public Map<String, Integer> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthSession actor) {
        return Map.of("deleted", users.deleteAccounts(List.of(id), actor.id()));
    }

    @PostMapping("/bulk-delete")
    public Map<String, Integer> bulkDelete(@Valid @RequestBody BulkDelete request,
            @AuthenticationPrincipal AuthSession actor) {
        return Map.of("deleted", users.deleteAccounts(request.userIds(), actor.id()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    public ResponseEntity<ApiError> notFound(NotFound exception) {
        String message = exception.getMessage() == null ? "User not found" : exception.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("USER_NOT_FOUND", message));
    }

    @ExceptionHandler({Conflict.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException exception) {
        String message = exception instanceof Conflict && exception.getMessage() != null
                ? exception.getMessage() : "User data conflicts with an existing account or reference";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("USER_CONFLICT", message));
    }

    public record ChangeStatus(@NotBlank String status) {
    }

    public record BulkStatus(@NotEmpty List<UUID> userIds, @NotBlank String status) {
    }

    public record BulkDelete(@NotEmpty List<UUID> userIds) {
    }

    public record ChangeRoleRequest(@NotBlank String role, @Email @Size(max = 255) String email,
            @Size(max = 50) String studentCode, @Size(max = 50) String advisorCode,
            UUID curriculumVersionId, UUID enrollmentSemesterId, @Positive Integer maxAdvisees) {
    }

    public record BulkRoleRequest(@NotBlank String role, @NotEmpty List<BulkRoleItem> items) {
    }

    public record EmailCheckRequest(@NotEmpty List<EmailCheckItem> items) {
    }

    public record CreateUserRequest(
            @NotBlank String role,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 255) String fullName,
            @Size(max = 20) String phone,
            @Size(max = 50) String studentCode,
            @Size(max = 50) String advisorCode,
            UUID curriculumVersionId,
            UUID enrollmentSemesterId,
            UUID expectedGraduationSemesterId,
            @Positive Integer currentSemester,
            @Positive Integer maxAdvisees) {
    }
}
