package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual student registration: the student picks an email and a password instead of signing in with Google.
 * The account is active immediately and the student profile is created in the same transaction.
 */
@Service
public class StudentRegistrationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final StudentOnboardingService profiles;

    public StudentRegistrationService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            StudentOnboardingService profiles) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.profiles = profiles;
    }

    @Transactional
    public UUID register(String fullName, String email, String password, LocalDate dateOfBirth, String studentCode,
            UUID curriculumVersionId, String phone) {
        String name = fullName == null ? "" : fullName.strip();
        if (name.length() < 2 || name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name must be 2 to 255 characters");
        }
        String loginEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (loginEmail.length() > 255 || !loginEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address");
        }
        requireStrongPassword(password);
        StudentOnboardingService.requireDateOfBirth(dateOfBirth);
        String code = StudentOnboardingService.normalizeStudentCode(studentCode);
        String cleanPhone = StudentOnboardingService.normalizePhone(phone);

        Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class,
                loginEmail);
        if (taken != null && taken > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        UUID userId = UUID.randomUUID();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone,
                                       date_of_birth, status)
                    SELECT ?, r.id, ?, ?, 'LOCAL', ?, ?, ?, 'ACTIVE' FROM roles r WHERE r.code = 'STUDENT'
                    """, userId.toString(), loginEmail, passwordEncoder.encode(password), name, cleanPhone,
                    dateOfBirth);
            if (inserted != 1) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Student role is not configured");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        profiles.addStudentProfile(userId, code, curriculumVersionId);
        return userId;
    }

    private static void requireStrongPassword(String password) {
        boolean ok = password != null && password.length() >= 8 && password.length() <= 72
                && password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be 8 to 72 characters and contain a letter and a digit");
        }
    }
}
