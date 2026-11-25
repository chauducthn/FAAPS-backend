package vn.edu.fpt.faaps.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AuthPasswordResetValidationTest {

    private JdbcTemplate jdbc;
    private AuthPasswordService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:pw_validate_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))
                """);
        jdbc.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255),
                    password_hash VARCHAR(255), auth_provider VARCHAR(20), full_name VARCHAR(255),
                    status VARCHAR(20), failed_login_attempts INT DEFAULT 0,
                    locked_until TIMESTAMP NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE password_reset_tokens (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36), token VARCHAR(255),
                    expires_at TIMESTAMP, used_at TIMESTAMP NULL, ip_address VARCHAR(45),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO roles (id, code) VALUES ('r-student', 'STUDENT')");
        jdbc.update("""
                INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, status)
                VALUES ('u-1', 'r-student', 'minh@fpt.edu.vn', 'old', 'LOCAL', 'Minh Tran', 'ACTIVE')
                """);
        service = new AuthPasswordService(jdbc);
    }

    @Test
    void validatesFreshTokenAndRejectsBlankUnknownAndUsedTokens() {
        assertFalse(service.isResetTokenValid(null));
        assertFalse(service.isResetTokenValid("   "));
        assertFalse(service.isResetTokenValid("nonexistent-token"));

        var result = service.forgotPassword("minh@fpt.edu.vn", "127.0.0.1");
        assertNotNull(result.resetToken());
        assertTrue(service.isResetTokenValid(result.resetToken()));

        service.resetPassword(result.resetToken(), "NewPass123");
        assertFalse(service.isResetTokenValid(result.resetToken()));
    }
}
