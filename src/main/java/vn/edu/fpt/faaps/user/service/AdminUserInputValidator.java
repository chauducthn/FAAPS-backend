package vn.edu.fpt.faaps.user.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

final class AdminUserInputValidator {

    private final JdbcTemplate jdbc;

    AdminUserInputValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String normalizePhone(String phone) {
        String clean = phone == null || phone.isBlank() ? null : phone.strip();
        if (clean != null && !StudentOnboardingService.PHONE.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "phone must be a 10-digit Vietnamese number starting with 0 (e.g. 0901234567)");
        }
        return clean;
    }

    void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must be at most " + maxLength + " characters");
        }
    }

    void validateStudentInput(CreateAccount input) {
        StudentCodeValidator.validateAndNormalize(input.studentCode());
        if (input.currentSemester() != null && input.currentSemester() < 1) {
            throw new IllegalArgumentException("currentSemester must be at least 1");
        }
        if (input.curriculumVersionId() != null) {
            List<String> curriculumStatus = jdbc.query("""
                    SELECT cv.status FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                    WHERE cv.id = ? AND m.status = 'ACTIVE'
                    """, (rs, rowNum) -> rs.getString("status"), input.curriculumVersionId().toString());
            if (curriculumStatus.isEmpty()) {
                throw new NotFound("Curriculum or its major was not found");
            }
            if (!"ACTIVE".equalsIgnoreCase(curriculumStatus.get(0))) {
                throw new Conflict("Student must be enrolled in an active curriculum");
            }
        }
        LocalDate enrollmentDate = input.enrollmentSemesterId() != null
                ? semesterStart(input.enrollmentSemesterId(), "Enrollment semester") : null;
        if (input.expectedGraduationSemesterId() != null) {
            LocalDate expectedDate = semesterStart(
                    input.expectedGraduationSemesterId(), "Expected graduation semester");
            if (enrollmentDate != null && expectedDate.isBefore(enrollmentDate)) {
                throw new IllegalArgumentException(
                        "expectedGraduationSemesterId cannot be before enrollmentSemesterId");
            }
        }
    }

    void validateAdvisorInput(CreateAccount input) {
        if (input.maxAdvisees() != null && input.maxAdvisees() < 1) {
            throw new IllegalArgumentException("maxAdvisees must be at least 1");
        }
    }

    private LocalDate semesterStart(UUID semesterId, String label) {
        return jdbc.query("SELECT start_date FROM semesters WHERE id = ?",
                (rs, rowNum) -> rs.getDate("start_date").toLocalDate(), semesterId.toString())
                .stream().findFirst().orElseThrow(() -> new NotFound(label + " was not found"));
    }
}
