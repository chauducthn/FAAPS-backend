package vn.edu.fpt.faaps.admin;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/avatars")
@ConditionalOnProperty(name = "faaps.avatar.enabled", havingValue = "true")
public class AvatarViewController {
    private final AvatarStorageService avatars;

    public AvatarViewController(AvatarStorageService avatars) {
        this.avatars = avatars;
    }

    @GetMapping("/{userId}/{imageId}")
    public ResponseEntity<byte[]> view(@PathVariable UUID userId, @PathVariable UUID imageId) {
        AvatarStorageService.Avatar avatar = avatars.read(userId, imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(avatar.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(avatar.bytes());
    }

    @ExceptionHandler(AdminUserService.NotFound.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.notFound().build();
    }
}
