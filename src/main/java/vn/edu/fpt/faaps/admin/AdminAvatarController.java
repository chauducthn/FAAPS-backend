package vn.edu.fpt.faaps.admin;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.ApiError;

@RestController
@RequestMapping("/api/admin/users")
@ConditionalOnProperty(name = "faaps.avatar.enabled", havingValue = "true")
public class AdminAvatarController {
    private final AvatarStorageService avatars;

    public AdminAvatarController(AvatarStorageService avatars) {
        this.avatars = avatars;
    }

    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return Map.of("avatarUrl", avatars.upload(id, file));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(AdminUserService.NotFound.class)
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("USER_NOT_FOUND", "User not found"));
    }
}
