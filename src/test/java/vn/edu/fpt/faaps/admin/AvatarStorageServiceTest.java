package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class AvatarStorageServiceTest {
    private JdbcTemplate jdbc;
    private S3Client s3;
    private AvatarStorageService avatars;
    private UUID userId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:avatars-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, avatar_url VARCHAR(500), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId.toString());
        s3 = mock(S3Client.class);
        avatars = new AvatarStorageService(jdbc, s3, "faaps-bucket");
    }

    @Test
    void uploadsPrivateImageAndReadsItThroughCurrentUrl() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        String avatarUrl = avatars.upload(userId,
                new MockMultipartFile("file", "avatar.png", "image/png", png));
        assertEquals(avatarUrl, jdbc.queryForObject("SELECT avatar_url FROM users WHERE id = ?",
                String.class, userId.toString()));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), png));
        UUID imageId = UUID.fromString(avatarUrl.substring(avatarUrl.lastIndexOf('/') + 1));
        AvatarStorageService.Avatar image = avatars.read(userId, imageId);
        assertArrayEquals(png, image.bytes());
        assertEquals("image/png", image.contentType());
    }

    @Test
    void rejectsSpoofedContentAndMissingUsers() {
        assertThrows(IllegalArgumentException.class, () -> avatars.upload(userId,
                new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes())));
        assertThrows(AdminUserService.NotFound.class, () -> avatars.upload(UUID.randomUUID(),
                new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})));
    }
}
