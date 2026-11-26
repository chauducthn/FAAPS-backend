package vn.edu.fpt.faaps.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.common.dto.ApiError;
import vn.edu.fpt.faaps.user.service.UserProfileService;
import vn.edu.fpt.faaps.user.service.UserProfileService.UserProfile;

@RestController
@RequestMapping("/api/profile")
public class UserProfileController {
    private final UserProfileService profiles;
    private final SecurityContextRepository securityContextRepository;

    public UserProfileController(UserProfileService profiles) {
        this(profiles, null);
    }

    @Autowired
    public UserProfileController(UserProfileService profiles, SecurityContextRepository securityContextRepository) {
        this.profiles = profiles;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping
    public UserProfile get(@AuthenticationPrincipal AuthSession session) {
        return profiles.getProfile(session.id());
    }

    @PutMapping
    public UserProfile update(
            @AuthenticationPrincipal AuthSession session,
            @Valid @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UserProfile updated = profiles.updateProfile(
                session.id(), request.fullName(), request.phone(), request.email(), request.dateOfBirth());
        syncSession(session, updated, httpRequest, httpResponse);
        return updated;
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserProfile uploadAvatar(
            @AuthenticationPrincipal AuthSession session,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UserProfile updated = profiles.updateAvatar(session.id(), file);
        syncSession(session, updated, httpRequest, httpResponse);
        return updated;
    }

    @DeleteMapping("/avatar")
    public UserProfile removeAvatar(
            @AuthenticationPrincipal AuthSession session,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        UserProfile updated = profiles.removeAvatar(session.id());
        syncSession(session, updated, httpRequest, httpResponse);
        return updated;
    }

    private void syncSession(AuthSession current, UserProfile updated,
            HttpServletRequest request, HttpServletResponse response) {
        if (current == null || securityContextRepository == null || request == null || response == null) {
            return;
        }
        AuthSession next = new AuthSession(
                current.id(),
                updated.fullName(),
                updated.email(),
                current.role(),
                current.status(),
                current.mustChangePassword(),
                updated.avatarUrl());
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null) {
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(next, null, existing.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
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
            String phone,
            @Email(message = "Please enter a valid email address")
            @Size(max = 255, message = "Email must be at most 255 characters")
            String email,
            LocalDate dateOfBirth) {}
}
