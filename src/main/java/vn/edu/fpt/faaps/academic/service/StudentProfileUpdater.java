package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.Profile;
import vn.edu.fpt.faaps.user.service.ExpectedGraduation;

final class StudentProfileUpdater {
    private static final Logger log = LoggerFactory.getLogger(StudentProfileUpdater.class);

    private final JdbcTemplate jdbc;
    private final StudentAcademicQueryRepository queries;

    StudentProfileUpdater(JdbcTemplate jdbc, StudentAcademicQueryRepository queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    Profile updateProfile(UUID studentId, String fullName, String phone) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName is required");
        }
        String compactPhone = phone == null ? null : phone.strip().replaceAll("\\s+", "");
        jdbc.update("UPDATE users SET full_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                fullName.strip(), StudentOnboardingService.normalizePhone(compactPhone), studentId.toString());
        return queries.profile(studentId);
    }

    Profile changeCurriculum(UUID studentId, UUID curriculumVersionId) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException("curriculumVersionId is required");
        }
        var curriculum = jdbc.query("""
                SELECT cv.major_id, cv.effective_from_semester_id
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                WHERE cv.id = ? AND cv.status = 'ACTIVE' AND m.status = 'ACTIVE'
                """, (rs, row) -> new String[] { rs.getString("major_id"), rs.getString("effective_from_semester_id") },
                curriculumVersionId.toString()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Curriculum is not available"));

        Integer gradesRecorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_course_records WHERE student_id = ? AND final_score IS NOT NULL",
                Integer.class, studentId.toString());
        if (gradesRecorded != null && gradesRecorded > 0) {
            String currentCurriculum = jdbc.queryForObject(
                    "SELECT curriculum_version_id FROM students WHERE user_id = ?",
                    String.class, studentId.toString());
            if (currentCurriculum != null && !currentCurriculum.equalsIgnoreCase(curriculumVersionId.toString())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot change curriculum once grades have been recorded. Please contact the Academic Office.");
            }
        }

        List<String> enrollmentList = jdbc.query(
                "SELECT enrollment_semester_id FROM students WHERE user_id = ?",
                (rs, rowNum) -> rs.getString("enrollment_semester_id"), studentId.toString());
        String enrollmentSemesterIdStr = enrollmentList.isEmpty() ? null : enrollmentList.get(0);
        if (enrollmentSemesterIdStr == null || enrollmentSemesterIdStr.isBlank()) {
            enrollmentSemesterIdStr = curriculum[1];
        }

        List<Integer> currentSemesterList = jdbc.query(
                "SELECT current_semester FROM students WHERE user_id = ?",
                (rs, rowNum) -> (Integer) rs.getObject("current_semester"), studentId.toString());
        Integer currentSemesterObj = currentSemesterList.isEmpty() ? null : currentSemesterList.get(0);
        int currentSemester = currentSemesterObj == null || currentSemesterObj < 1 ? 1 : currentSemesterObj;

        UUID enrollmentSemesterUuid = parseUuidOrNull(enrollmentSemesterIdStr);
        String expectedGraduationId = enrollmentSemesterUuid != null
                ? ExpectedGraduation.semesterId(jdbc, curriculumVersionId, enrollmentSemesterUuid, currentSemester)
                : null;

        int updated = jdbc.update("""
                UPDATE students
                SET major_id = ?, curriculum_version_id = ?,
                    enrollment_semester_id = ?,
                    expected_graduation_semester_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """, curriculum[0], curriculumVersionId.toString(),
                enrollmentSemesterIdStr, expectedGraduationId, studentId.toString());

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student profile not found");
        }
        return queries.profile(studentId);
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring malformed enrollment semester UUID '{}': {}", value, ex.getMessage());
            return null;
        }
    }
}
