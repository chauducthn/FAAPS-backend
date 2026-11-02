package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.common.validation.IdentityInput;

/**
 * Lets a student who signed in with Google for the first time finish registration. The first Google sign-in
 * creates the user without an academic profile; this service adds the student profile (Student ID, major and
 * curriculum) that an administrator would otherwise have to enter.
 */
@Service
public class StudentOnboardingService {
    private final JdbcTemplate jdbc;

    public StudentOnboardingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CurriculumChoice> curriculumChoices() {
        return jdbc.query("""
                SELECT cv.id, cv.version, cv.name, m.code AS major_code, m.name AS major_name
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                WHERE cv.status = 'ACTIVE' AND m.status = 'ACTIVE'
                ORDER BY m.code, cv.version
                """, (rs, row) -> new CurriculumChoice(rs.getString("id"), rs.getString("version"),
                rs.getString("name"), rs.getString("major_code"), rs.getString("major_name")));
    }

    @Transactional
    public void complete(UUID userId, String studentCode, UUID curriculumVersionId, LocalDate dateOfBirth,
            String phone) {
        String code = normalizeStudentCode(studentCode);
        String cleanPhone = normalizePhone(phone);
        requireDateOfBirth(dateOfBirth);

        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id = ?", Integer.class,
                userId.toString());
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your academic profile is already set up");
        }

        addStudentProfile(userId, code, curriculumVersionId);
        jdbc.update("UPDATE users SET date_of_birth = ?, phone = COALESCE(?, phone), updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", dateOfBirth, cleanPhone, userId.toString());
    }

    /** Inserts the students row for an existing user. The caller has already validated and normalized the input. */
    void addStudentProfile(UUID userId, String code, UUID curriculumVersionId) {
        if (curriculumVersionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Curriculum is required");
        }
        var curriculum = jdbc.query("""
                SELECT cv.major_id, cv.effective_from_semester_id
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                WHERE cv.id = ? AND cv.status = 'ACTIVE' AND m.status = 'ACTIVE'
                """, (rs, row) -> new String[] {rs.getString("major_id"), rs.getString("effective_from_semester_id")},
                curriculumVersionId.toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This curriculum is not available"));
        try {
            jdbc.update("""
                    INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
                                          enrollment_semester_id, current_semester, current_gpa,
                                          total_credits_earned, academic_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 1, 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, userId.toString(), code, curriculum[0], curriculumVersionId.toString(), curriculum[1]);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This Student ID is already registered");
        }
    }

    static String normalizeStudentCode(String studentCode) {
        try {
            return IdentityInput.studentCode(studentCode);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    static String normalizePhone(String phone) {
        try {
            return IdentityInput.phone(phone);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    static void requireDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of birth is required");
        }
        if (!dateOfBirth.isBefore(LocalDate.now()) || dateOfBirth.isBefore(LocalDate.now().minusYears(100))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid date of birth");
        }
    }

    public record CurriculumChoice(String id, String version, String name, String majorCode, String majorName) {}
}
