package vn.edu.fpt.faaps.admin;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(name = "faaps.avatar.enabled", havingValue = "true")
public class AvatarStorageService {
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final String bucket;

    public AvatarStorageService(JdbcTemplate jdbc, S3Client s3,
            @Value("${faaps.avatar.bucket}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("FAAPS_AVATAR_BUCKET is required when avatar upload is enabled");
        }
        this.jdbc = jdbc;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String upload(UUID userId, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Avatar must be between 1 byte and 2 MB");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Avatar must be between 1 byte and 2 MB");
        }
        String contentType = imageType(bytes);
        if (contentType == null || !contentType.equals(file.getContentType())) {
            throw new IllegalArgumentException("Avatar must be a JPEG, PNG, or WebP image");
        }
        var previousUrls = jdbc.query("SELECT avatar_url FROM users WHERE id = ?",
                (result, row) -> result.getString(1), userId.toString());
        if (previousUrls.isEmpty()) {
            throw new AdminUserService.NotFound();
        }
        String previous = previousUrls.get(0);
        UUID imageId = UUID.randomUUID();
        String key = "avatars/" + userId + "/" + imageId;
        String avatarUrl = "/api/avatars/" + userId + "/" + imageId;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes));
        int changed = jdbc.update("UPDATE users SET avatar_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                avatarUrl, userId.toString());
        if (changed == 0) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            throw new AdminUserService.NotFound();
        }
        if (previous != null && previous.startsWith("/api/avatars/" + userId + "/")) {
            String oldKey = "avatars/" + userId + "/" + previous.substring(previous.lastIndexOf('/') + 1);
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(oldKey).build());
            } catch (RuntimeException ignored) {
            }
        }
        return avatarUrl;
    }

    public Avatar read(UUID userId, UUID imageId) {
        String avatarUrl = "/api/avatars/" + userId + "/" + imageId;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND avatar_url = ?",
                Long.class, userId.toString(), avatarUrl);
        if (count == null || count == 0) {
            throw new AdminUserService.NotFound();
        }
        var object = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                .key("avatars/" + userId + "/" + imageId).build());
        byte[] bytes = object.asByteArray();
        String contentType = imageType(bytes);
        if (contentType == null) {
            throw new IllegalStateException("Stored avatar is not an image");
        }
        return new Avatar(bytes, contentType);
    }

    private String imageType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), PNG_SIGNATURE)) {
            return "image/png";
        }
        if (bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    public record Avatar(byte[] bytes, String contentType) {
    }
}
