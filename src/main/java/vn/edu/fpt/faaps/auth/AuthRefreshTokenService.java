package vn.edu.fpt.faaps.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRefreshTokenService {
    public static final String COOKIE_NAME = "FAAPS_REFRESH_TOKEN";
    private static final String COOKIE_PATH = "/api/auth";
    private final JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration tokenLifetime;
    private final boolean secureCookie;

    public AuthRefreshTokenService(JdbcTemplate jdbc,
            @Value("${faaps.auth.refresh-token-lifetime:14d}") Duration tokenLifetime,
            @Value("${faaps.auth.cookie-secure:false}") boolean secureCookie) {
        if (tokenLifetime.isZero() || tokenLifetime.isNegative()) {
            throw new IllegalArgumentException("Refresh token lifetime must be positive");
        }
        this.jdbc = jdbc;
        this.tokenLifetime = tokenLifetime;
        this.secureCookie = secureCookie;
    }

    @Transactional
    public void issueCookie(UUID userId, HttpServletRequest request, HttpServletResponse response) {
        String rawToken = newRawToken();
        insertToken(userId, rawToken, request);
        writeCookie(response, rawToken);
    }

    public UUID userIdFor(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        try {
            List<UUID> ids = jdbc.query("""
                    SELECT user_id FROM refresh_tokens
                    WHERE token = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                    """, (rs, rowNum) -> UUID.fromString(rs.getString("user_id")), hash(rawToken));
            return ids.stream().findFirst().orElseThrow(InvalidRefreshTokenException::new);
        } catch (DataAccessException exception) {
            throw new AuthUnavailableException();
        }
    }

    @Transactional
    public String rotate(String rawToken, UUID userId, HttpServletRequest request) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        try {
            int revoked = jdbc.update("""
                    UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
                    WHERE token = ? AND user_id = ? AND revoked_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP
                    """, hash(rawToken), userId.toString());
            if (revoked != 1) {
                throw new InvalidRefreshTokenException();
            }
            String replacement = newRawToken();
            insertToken(userId, replacement, request);
            return replacement;
        } catch (InvalidRefreshTokenException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new AuthUnavailableException();
        }
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        try {
            jdbc.update("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE token = ? AND revoked_at IS NULL",
                    hash(rawToken));
        } catch (DataAccessException exception) {
            throw new AuthUnavailableException();
        }
    }

    public void revokeFromCookie(HttpServletRequest request) {
        revoke(cookieValue(request));
    }

    public void writeCookie(HttpServletResponse response, String rawToken) {
        Cookie cookie = new Cookie(COOKIE_NAME, rawToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(Math.toIntExact(tokenLifetime.toSeconds()));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void insertToken(UUID userId, String rawToken, HttpServletRequest request) {
        Instant now = Instant.now();
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 500) {
            userAgent = userAgent.substring(0, 500);
        }
        try {
            jdbc.update("""
                    INSERT INTO refresh_tokens (id, user_id, token, expires_at, ip_address, user_agent, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), userId.toString(), hash(rawToken),
                    Timestamp.from(now.plus(tokenLifetime)), request.getRemoteAddr(), userAgent);
        } catch (DataAccessException exception) {
            throw new AuthUnavailableException();
        }
    }

    private String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String newRawToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
    }
}
