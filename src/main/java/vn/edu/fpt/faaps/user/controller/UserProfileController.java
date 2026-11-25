package vn.edu.fpt.faaps.user.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;
import vn.edu.fpt.faaps.user.service.UserProfileService;
import vn.edu.fpt.faaps.user.service.UserProfileService.UserProfile;

@RestController
@RequestMapping("/api/profile")
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public UserProfile get(@AuthenticationPrincipal AuthSession session) {
        return profiles.getProfile(session.id());
    }

    @PutMapping
    public UserProfile update(
            @AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.updateProfile(session.id(), request.fullName(), request.phone());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Full name is required")
            @Size(min = 2, max = 255, message = "Full name must be between 2 and 255 characters")
            String fullName,
            @Size(max = 20, message = "Phone must be at most 20 characters")
            String phone) {}
}
