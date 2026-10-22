package vn.edu.fpt.faaps.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRefreshTokenServiceTest {
    private JdbcTemplate jdbc;
    private AuthRefreshTokenService refreshTokens;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:refresh-tokens-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE refresh_tokens (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
                    token VARCHAR(500) UNIQUE NOT NULL, expires_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP, ip_address VARCHAR(45), user_agent VARCHAR(500),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        refreshTokens = new AuthRefreshTokenService(jdbc, Duration.ofDays(14), false);
    }

    @Test
    void issuesHashedHttpOnlyCookieAndRotatesTokenOnlyOnce() {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "FAAPS test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        refreshTokens.issueCookie(userId, request, response);
        Cookie cookie = response.getCookie(AuthRefreshTokenService.COOKIE_NAME);
        String firstToken = cookie.getValue();

        assertEquals(true, cookie.isHttpOnly());
        assertEquals(false, cookie.getSecure());
        assertEquals(userId, refreshTokens.userIdFor(firstToken));
        assertNotEquals(firstToken, jdbc.queryForObject("SELECT token FROM refresh_tokens", String.class));

        String nextToken = refreshTokens.rotate(firstToken, userId, request);
        assertNotEquals(firstToken, nextToken);
        assertEquals(userId, refreshTokens.userIdFor(nextToken));
        assertThrows(AuthRefreshTokenService.InvalidRefreshTokenException.class,
                () -> refreshTokens.userIdFor(firstToken));
        assertThrows(AuthRefreshTokenService.InvalidRefreshTokenException.class,
                () -> refreshTokens.rotate(firstToken, userId, request));
    }

    @Test
    void rejectsExpiredAndRevokedTokens() {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        refreshTokens.issueCookie(userId, request, response);
        String token = response.getCookie(AuthRefreshTokenService.COOKIE_NAME).getValue();
        jdbc.update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY");

        assertThrows(AuthRefreshTokenService.InvalidRefreshTokenException.class,
                () -> refreshTokens.userIdFor(token));
    }

    @Test
    void logoutRevokesRefreshCookie() {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        refreshTokens.issueCookie(userId, request, response);
        String token = response.getCookie(AuthRefreshTokenService.COOKIE_NAME).getValue();
        request.setCookies(new Cookie(AuthRefreshTokenService.COOKIE_NAME, token));

        refreshTokens.revokeFromCookie(request);

        assertThrows(AuthRefreshTokenService.InvalidRefreshTokenException.class,
                () -> refreshTokens.userIdFor(token));
    }
}
