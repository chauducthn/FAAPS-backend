package vn.edu.fpt.faaps.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class UserProfileServiceTest {
    private JdbcTemplate jdbc;
    private UserProfileService service;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:user_profile_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))");
        jdbc.execute("""
                CREATE TABLE users (
                  id VARCHAR(36) PRIMARY KEY,
                  role_id VARCHAR(36) NOT NULL,
                  email VARCHAR(255) NOT NULL,
                  full_name VARCHAR(255) NOT NULL,
                  phone VARCHAR(20),
                  date_of_birth DATE,
                  status VARCHAR(20) DEFAULT 'ACTIVE',
                  auth_provider VARCHAR(20) DEFAULT 'LOCAL',
                  last_login_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        String roleId = UUID.randomUUID().toString();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO roles(id, code) VALUES (?, 'ADMIN')", roleId);
        jdbc.update(
                "INSERT INTO users(id, role_id, email, full_name, phone) VALUES (?, ?, 'admin@fpt.edu.vn', 'System Admin', '0901 234 567')",
                adminId.toString(),
                roleId);
        service = new UserProfileService(jdbc);
    }

    @Test
    void getProfileReturnsNormalizedPhoneAndRole() {
        var profile = service.getProfile(adminId);
        assertEquals("System Admin", profile.fullName());
        assertEquals("0901234567", profile.phone());
        assertEquals("ADMIN", profile.role());
    }

    @Test
    void updateProfilePersistsTrimmedNameAndOptionalPhone() {
        var updated = service.updateProfile(adminId, "  Admin Updated  ", "0912-345-678");
        assertEquals("Admin Updated", updated.fullName());
        assertEquals("0912345678", updated.phone());

        var cleared = service.updateProfile(adminId, "Admin Updated", "   ");
        assertNull(cleared.phone());
    }

    @Test
    void updateProfileRejectsInvalidInputsAndMissingUser() {
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(adminId, "A", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(adminId, "Valid Name", "123"));
        assertThrows(ResponseStatusException.class, () -> service.getProfile(UUID.randomUUID()));
    }
}
