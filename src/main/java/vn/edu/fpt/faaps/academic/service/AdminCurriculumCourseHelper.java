package vn.edu.fpt.faaps.academic.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.repository.CurriculumReadRepository;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.Conflict;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.CurriculumCourseItem;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.NotFound;

final class AdminCurriculumCourseHelper {
    private final JdbcTemplate jdbc;
    private final CurriculumReadRepository reads;
    private final AdminCurriculumValidator validator;

    AdminCurriculumCourseHelper(JdbcTemplate jdbc, CurriculumReadRepository reads, AdminCurriculumValidator validator) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.validator = validator;
    }

    List<CurriculumCourseItem> addCourses(UUID curriculumId, List<UUID> courseIds,
            Integer semesterNumber, Boolean mandatory) {
        validator.lockCurriculum(curriculumId);
        if (courseIds == null || courseIds.isEmpty() || courseIds.size() > 100) {
            throw new IllegalArgumentException("courseIds must contain between 1 and 100 IDs");
        }
        Set<UUID> uniqueCourseIds = new HashSet<>(courseIds);
        if (uniqueCourseIds.size() != courseIds.size() || uniqueCourseIds.contains(null)) {
            throw new IllegalArgumentException("courseIds must contain unique, non-null IDs");
        }
        if (semesterNumber == null || semesterNumber < 0 || semesterNumber > 12) {
            throw new IllegalArgumentException("semesterNumber must be between 0 and 12");
        }
        int incomingCredits = 0;
        for (UUID courseId : courseIds) {
            List<Object[]> rows = jdbc.query("SELECT status, credits FROM courses WHERE id = ?",
                    (rs, rowNum) -> new Object[] { rs.getString("status"), rs.getInt("credits") }, courseId.toString());
            if (rows.isEmpty()) {
                throw new NotFound("Course not found: " + courseId);
            }
            if (!"ACTIVE".equalsIgnoreCase((String) rows.get(0)[0])) {
                throw new Conflict("Only active courses can be added to a curriculum: " + courseId);
            }
            incomingCredits += (Integer) rows.get(0)[1];
        }
        Integer majorLimit = jdbc.queryForObject(
                "SELECT COALESCE(m.total_credits, 0) FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id WHERE cv.id = ?",
                Integer.class, curriculumId.toString());
        Integer currentCredits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(c.credits), 0) FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id WHERE cc.curriculum_version_id = ?",
                Integer.class, curriculumId.toString());
        int nextTotal = (currentCredits != null ? currentCredits : 0) + incomingCredits;
        if (majorLimit != null && majorLimit > 0 && nextTotal > majorLimit) {
            throw new Conflict("Total course credits (" + nextTotal
                    + ") cannot exceed major credit limit (" + majorLimit + ")");
        }
        try {
            for (UUID courseId : courseIds) {
                jdbc.update("""
                        INSERT INTO curriculum_courses
                            (id, curriculum_version_id, course_id, semester_number, is_mandatory,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID().toString(), curriculumId.toString(), courseId.toString(),
                        semesterNumber, mandatory == null || mandatory);
            }
        } catch (DuplicateKeyException exception) {
            throw new Conflict("At least one course is already included in the curriculum");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Courses could not be added because a referenced record is invalid");
        }
        Set<UUID> requested = Set.copyOf(courseIds);
        return reads.courses(curriculumId).stream()
                .filter(course -> requested.contains(course.courseId()))
                .toList();
    }

    CurriculumCourseItem updateCourse(UUID curriculumId, UUID courseId,
            Integer semesterNumber, Boolean mandatory, String groupName) {
        validator.lockCurriculum(curriculumId);
        if (semesterNumber == null || semesterNumber < 0 || semesterNumber > 12) {
            throw new IllegalArgumentException("semesterNumber must be between 0 and 12");
        }
        int updated = jdbc.update("""
                UPDATE curriculum_courses SET semester_number = ?, is_mandatory = ?, group_name = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE curriculum_version_id = ? AND course_id = ?
                """, semesterNumber, mandatory == null || mandatory,
                groupName == null || groupName.isBlank() ? null : groupName.strip(),
                curriculumId.toString(), courseId.toString());
        if (updated == 0) {
            throw new NotFound("Course is not included in this curriculum");
        }
        return reads.courses(curriculumId).stream()
                .filter(course -> course.courseId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new NotFound("Curriculum course not found"));
    }

    void removeCourse(UUID curriculumId, UUID courseId) {
        validator.lockCurriculum(curriculumId);
        int deleted = jdbc.update(
                "DELETE FROM curriculum_courses WHERE curriculum_version_id = ? AND course_id = ?",
                curriculumId.toString(), courseId.toString());
        if (deleted == 0) {
            throw new NotFound("Course is not included in this curriculum");
        }
    }
}
