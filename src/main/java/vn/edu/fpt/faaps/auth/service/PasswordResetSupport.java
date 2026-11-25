package vn.edu.fpt.faaps.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;

final class PasswordResetSupport {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetSupport.class);

    /** At least 8 characters, at most 72 (BCrypt limit), with at least one letter and one digit. */
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$");
    private static final long RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000L;
    private static final int MAX_REQUESTS_PER_EMAIL = 3;
    private static final int MAX_REQUESTS_PER_IP = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String frontendUrl;
    private final EmailService emailService;
    private final Map<String, List<Long>> emailRateLimiter = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> ipRateLimiter = new ConcurrentHashMap<>();

    PasswordResetSupport(String frontendUrl, EmailService emailService) {
        this.frontendUrl = frontendUrl;
        this.emailService = emailService;
    }

    void checkRateLimit(String normalizedEmail, String ipAddress) {
        long now = System.currentTimeMillis();
        if (!allowRequest(emailRateLimiter, normalizedEmail, now, MAX_REQUESTS_PER_EMAIL)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password reset requests for this email. Please try again in 15 minutes.");
        }
        if (ipAddress != null && !ipAddress.isBlank()
                && !allowRequest(ipRateLimiter, ipAddress.strip(), now, MAX_REQUESTS_PER_IP)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password reset requests from this IP. Please try again in 15 minutes.");
        }
    }

    void clearRateLimits() {
        emailRateLimiter.clear();
        ipRateLimiter.clear();
    }

    void dispatchPasswordResetEmail(String email, String fullName, String rawToken, int ttlMinutes) {
        String base = frontendUrl == null ? "http://localhost:5173" : frontendUrl.replaceAll("/+$", "");
        String resetUrl = base + "/reset-password?token=" + rawToken;
        log.info("Password reset requested for {} ({}). Reset link dispatched to mail handler.", email, fullName);
        if (emailService != null) {
            emailService.sendPasswordResetLink(email, fullName, resetUrl, ttlMinutes);
        }
    }

    static void validatePassword(String password) {
        if (password == null || !PASSWORD.matcher(password).matches()) {
            throw new IllegalArgumentException(
                    "Password must be 8-72 characters with at least one letter and one number");
        }
    }

    static String generateResetToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static boolean allowRequest(Map<String, List<Long>> store, String key, long now, int maxRequests) {
        List<Long> timestamps = store.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (timestamps) {
            timestamps.removeIf(ts -> (now - ts) > RATE_LIMIT_WINDOW_MS);
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.add(now);
            return true;
        }
    }
}
