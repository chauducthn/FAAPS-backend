package vn.edu.fpt.faaps.academic.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual student registration: the student picks an email and a password instead of signing in with Google.
 * The account is active immediately. The major is not asked here: without a curriculum only the user is created and
 * the student chooses the curriculum on the next step (the same step a Google student goes through); when a curriculum
 * is given the student profile is created in the same transaction.
 */
@Service
public class StudentRegistrationService {
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

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
        StudentOnboardingService.requireDateOfBirth(dateOfBirth);
        String code = StudentOnboardingService.normalizeStudentCode(studentCode);
        String cleanPhone = StudentOnboardingService.normalizePhone(phone);
        if (cleanPhone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number is required");
        }
        requireStrongPassword(password, name, loginEmail, code);

        Integer taken = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Integer.class,
                loginEmail);
        if (taken != null && taken > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        profiles.requireStudentCodeAvailable(code);

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

    static void requireStrongPassword(String password, String fullName, String email, String studentCode) {
        boolean ok = password != null && password.length() >= 8 && password.length() <= 72
                && LOWER.matcher(password).find() && UPPER.matcher(password).find()
                && DIGIT.matcher(password).find() && SPECIAL.matcher(password).find();
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be 8 to 72 characters with an uppercase letter, a lowercase letter, a digit "
                            + "and a special character");
        }
        if (containsPersonalInfo(password, fullName, email, studentCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must not contain your Student ID, name or email");
        }
    }

    /** True when the password contains the Student ID, a word of the name, or the part of the email before "@". */
    static boolean containsPersonalInfo(String password, String fullName, String email, String studentCode) {
        String target = fold(password);
        String code = fold(studentCode);
        String name = fold(fullName);
        int at = email.indexOf('@');
        String emailName = fold(at < 0 ? email : email.substring(0, at));

        List<String> parts = new ArrayList<>();
        addIfLongEnough(parts, code, 3);
        if (code.length() > 2) {
            addIfLongEnough(parts, code.substring(2), 6);
        }
        addIfLongEnough(parts, name.replaceAll("\\s+", ""), 3);
        for (String word : name.split("\\s+")) {
            addIfLongEnough(parts, word, 3);
        }
        addIfLongEnough(parts, emailName, 3);
        return parts.stream().anyMatch(target::contains);
    }

    private static void addIfLongEnough(List<String> parts, String part, int minLength) {
        if (part.length() >= minLength) {
            parts.add(part);
        }
    }

    private static String fold(String value) {
        String plain = Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return plain.toLowerCase(Locale.ROOT);
    }
}
