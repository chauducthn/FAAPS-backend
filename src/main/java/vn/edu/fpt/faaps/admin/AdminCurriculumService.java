package vn.edu.fpt.faaps.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCurriculumService {
    private final JdbcTemplate jdbc;
    private final CurriculumReadRepository reads;

    public AdminCurriculumService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reads = new CurriculumReadRepository(jdbc);
    }

    public Page list(UUID majorId, String status, Boolean isCurrent, String search, int page, int size) {
        return reads.list(majorId,status,isCurrent,search,page,size);
    }

    public CurriculumItem detail(UUID id) {
        return reads.detail(id);
    }

    public List<CurriculumCourseItem> courses(UUID curriculumId) {
        return reads.courses(curriculumId);
    }

    @Transactional
    public CurriculumItem create(CurriculumInput input) {
        validateInput(input);
        String status = normalizeStatus(input.status(), "ACTIVE");
        boolean current = Boolean.TRUE.equals(input.isCurrent());
        if (current && !"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("An inactive curriculum cannot be current");
        }
        requireMajor(input.majorId());
        validateSemesters(input.effectiveFromSemesterId(), input.effectiveToSemesterId());
        lockMajors(List.of(input.majorId()));

        UUID id = UUID.randomUUID();
        try {
            if (current) {
                clearCurrent(input.majorId(), null);
            }
            jdbc.update("""
                    INSERT INTO curriculum_versions
                        (id, major_id, version, name, effective_from_semester_id,
                         effective_to_semester_id, description, is_current, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, id.toString(), input.majorId().toString(), input.version().strip(), input.name().strip(),
                    input.effectiveFromSemesterId().toString(), nullableUuid(input.effectiveToSemesterId()),
                    input.description(), current, status);
        } catch (DuplicateKeyException exception) {
            throw new Conflict("A curriculum with this version already exists for the major");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Curriculum references invalid related records");
        }
        return detail(id);
    }

    @Transactional
    public CurriculumItem update(UUID id, CurriculumInput input) {
        validateInput(input);
        String status = normalizeStatus(input.status(), null);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        boolean current = Boolean.TRUE.equals(input.isCurrent());
        if (current && !"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("An inactive curriculum cannot be current");
        }

        CurriculumItem observed = detail(id);
        requireMajor(input.majorId());
        validateSemesters(input.effectiveFromSemesterId(), input.effectiveToSemesterId());
        // Keep the lock order consistent with create(): major rows before curriculum rows.
        lockMajors(List.of(observed.majorId(), input.majorId()));
        CurriculumItem old = lockCurriculum(id);
        if (!old.majorId().equals(observed.majorId())) {
            throw new Conflict("Curriculum changed while this update was being prepared; retry the request");
        }
        try {
            if (current) {
                clearCurrent(input.majorId(), id);
            }
            jdbc.update("""
                    UPDATE curriculum_versions
                    SET major_id = ?, version = ?, name = ?, effective_from_semester_id = ?,
                        effective_to_semester_id = ?, description = ?, is_current = ?, status = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, input.majorId().toString(), input.version().strip(), input.name().strip(),
                    input.effectiveFromSemesterId().toString(), nullableUuid(input.effectiveToSemesterId()),
                    input.description(), current, status, id.toString());
        } catch (DuplicateKeyException exception) {
            throw new Conflict("A curriculum with this version already exists for the major");
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Curriculum references invalid related records");
        }
        return detail(id);
    }

    @Transactional
    public CurriculumItem updateStatus(UUID id, String requestedStatus) {
        String status = normalizeStatus(requestedStatus, null);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        CurriculumItem current = lockCurriculum(id);
        if ("INACTIVE".equals(status)) {
            jdbc.update("""
                    UPDATE curriculum_versions
                    SET status = 'INACTIVE', is_current = FALSE, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, id.toString());
        } else {
            jdbc.update("UPDATE curriculum_versions SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    id.toString());
        }
        return detail(current.id());
    }

    @Transactional
    public void delete(UUID id) {
        CurriculumItem curriculum = lockCurriculum(id);
        List<ReferenceCount> references = List.of(
                reference("students", "SELECT COUNT(*) FROM students WHERE curriculum_version_id = ?", id),
                reference("graduation checks", "SELECT COUNT(*) FROM graduation_checks WHERE curriculum_version_id = ?", id),
                reference("curriculum courses", "SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ?", id),
                reference("academic policies", "SELECT COUNT(*) FROM academic_policies WHERE curriculum_version_id = ?", id),
                reference("graduation requirements", "SELECT COUNT(*) FROM graduation_requirements WHERE curriculum_version_id = ?", id),
                reference("advisor assignments", "SELECT COUNT(*) FROM cohort_advisors WHERE cohort_id = ?", id));
        List<String> usedBy = references.stream().filter(ref -> ref.count() > 0)
                .map(ref -> ref.name() + " (" + ref.count() + ")").toList();
        if (!usedBy.isEmpty()) {
            throw new Conflict("Cannot delete curriculum/cohort while referenced by: " + String.join(", ", usedBy));
        }
        try {
            jdbc.update("DELETE FROM curriculum_versions WHERE id = ?", curriculum.id().toString());
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Cannot delete curriculum/cohort because related records still exist");
        }
    }

    @Transactional
    public List<CurriculumCourseItem> addCourses(UUID curriculumId, List<UUID> courseIds,
            Integer semesterNumber, Boolean mandatory) {
        lockCurriculum(curriculumId);
        if (courseIds == null || courseIds.isEmpty() || courseIds.size() > 100) {
            throw new IllegalArgumentException("courseIds must contain between 1 and 100 IDs");
        }
        Set<UUID> uniqueCourseIds = new HashSet<>(courseIds);
        if (uniqueCourseIds.size() != courseIds.size() || uniqueCourseIds.contains(null)) {
            throw new IllegalArgumentException("courseIds must contain unique, non-null IDs");
        }
        if (semesterNumber == null || semesterNumber < 1) {
            throw new IllegalArgumentException("semesterNumber must be at least 1");
        }
        for (UUID courseId : courseIds) {
            List<String> courseStatuses = jdbc.query("SELECT status FROM courses WHERE id = ?",
                    (rs, rowNum) -> rs.getString("status"), courseId.toString());
            if (courseStatuses.isEmpty()) {
                throw new NotFound("Course not found: " + courseId);
            }
            if (!"ACTIVE".equalsIgnoreCase(courseStatuses.get(0))) {
                throw new Conflict("Only active courses can be added to a curriculum: " + courseId);
            }
        }
        try {
            for (UUID courseId : courseIds) {
                jdbc.update("""
                        INSERT INTO curriculum_courses
                            (id, curriculum_version_id, course_id, semester_number, is_mandatory, created_at, updated_at)
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
        return courses(curriculumId).stream().filter(course -> requested.contains(course.courseId())).toList();
    }

    @Transactional
    public CurriculumCourseItem updateCourse(UUID curriculumId, UUID courseId,
            Integer semesterNumber, Boolean mandatory, String groupName) {
        lockCurriculum(curriculumId);
        if (semesterNumber == null || semesterNumber < 1 || semesterNumber > 20) {
            throw new IllegalArgumentException("semesterNumber must be between 1 and 20");
        }
        int updated = jdbc.update("""
                UPDATE curriculum_courses SET semester_number = ?, is_mandatory = ?, group_name = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE curriculum_version_id = ? AND course_id = ?
                """, semesterNumber, mandatory == null || mandatory,
                groupName == null || groupName.isBlank() ? null : groupName.strip(),
                curriculumId.toString(), courseId.toString());
        if (updated == 0) throw new NotFound("Course is not included in this curriculum");
        return courses(curriculumId).stream().filter(course -> course.courseId().equals(courseId))
                .findFirst().orElseThrow(() -> new NotFound("Curriculum course not found"));
    }

    @Transactional
    public void removeCourse(UUID curriculumId, UUID courseId) {
        lockCurriculum(curriculumId);
        int deleted = jdbc.update("DELETE FROM curriculum_courses WHERE curriculum_version_id = ? AND course_id = ?",
                curriculumId.toString(), courseId.toString());
        if (deleted == 0) throw new NotFound("Course is not included in this curriculum");
    }

    private CurriculumItem lockCurriculum(UUID id) {
        List<UUID> ids = jdbc.query("SELECT id FROM curriculum_versions WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")), id.toString());
        if (ids.isEmpty()) {
            throw new NotFound("Curriculum or cohort not found");
        }
        return detail(id);
    }

    private void requireMajor(UUID id) {
        if (id == null || Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM majors WHERE id = ?)", Boolean.class, id.toString()))) {
            throw new NotFound("Major not found");
        }
    }

    private void validateSemesters(UUID fromId, UUID toId) {
        if (fromId == null) {
            throw new IllegalArgumentException("effectiveFromSemesterId is required");
        }
        List<LocalDate> fromDates = jdbc.query("SELECT start_date FROM semesters WHERE id = ?",
                (rs, rowNum) -> rs.getDate("start_date").toLocalDate(), fromId.toString());
        if (fromDates.isEmpty()) {
            throw new NotFound("Effective-from semester not found");
        }
        if (toId == null) {
            return;
        }
        List<LocalDate> toDates = jdbc.query("SELECT start_date FROM semesters WHERE id = ?",
                (rs, rowNum) -> rs.getDate("start_date").toLocalDate(), toId.toString());
        if (toDates.isEmpty()) {
            throw new NotFound("Effective-to semester not found");
        }
        if (toDates.get(0).isBefore(fromDates.get(0))) {
            throw new IllegalArgumentException("effectiveToSemesterId cannot be before effectiveFromSemesterId");
        }
    }

    private void lockMajors(List<UUID> majorIds) {
        majorIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(id -> {
            List<String> found = jdbc.query("SELECT id FROM majors WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString("id"), id.toString());
            if (found.isEmpty()) {
                throw new NotFound("Major not found");
            }
        });
    }

    private void clearCurrent(UUID majorId, UUID exceptId) {
        if (exceptId == null) {
            jdbc.update("UPDATE curriculum_versions SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP WHERE major_id = ? AND is_current = TRUE",
                    majorId.toString());
        } else {
            jdbc.update("UPDATE curriculum_versions SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP WHERE major_id = ? AND is_current = TRUE AND id <> ?",
                    majorId.toString(), exceptId.toString());
        }
    }

    private ReferenceCount reference(String name, String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id.toString());
        return new ReferenceCount(name, count == null ? 0 : count);
    }

    private void validateInput(CurriculumInput input) {
        if (input == null || input.majorId() == null) {
            throw new IllegalArgumentException("majorId is required");
        }
        if (input.version() == null || input.version().isBlank() || input.version().length() > 50) {
            throw new IllegalArgumentException("version is required and must be at most 50 characters");
        }
        if (input.name() == null || input.name().isBlank() || input.name().length() > 255) {
            throw new IllegalArgumentException("name is required and must be at most 255 characters");
        }
        if (input.effectiveFromSemesterId() == null) {
            throw new IllegalArgumentException("effectiveFromSemesterId is required");
        }
    }

    private String normalizeStatus(String status, String defaultValue) {
        if (status == null || status.isBlank()) {
            return defaultValue;
        }
        String normalized = status.strip().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
        }
        return normalized;
    }

    private Object nullableUuid(UUID id) {
        return id == null ? null : id.toString();
    }

    public record CurriculumInput(UUID majorId, String version, String name, UUID effectiveFromSemesterId,
            UUID effectiveToSemesterId, String description, Boolean isCurrent, String status) {
    }

    public record CurriculumItem(UUID id, UUID majorId, String majorCode, String majorName, String version,
            String name, UUID effectiveFromSemesterId, String effectiveFromSemesterCode, LocalDate effectiveFromDate,
            UUID effectiveToSemesterId, String effectiveToSemesterCode, LocalDate effectiveToDate,
            String description, boolean isCurrent, String status, int courseCount,
            int totalCredits, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CurriculumCourseItem(UUID id, UUID curriculumId, UUID courseId, String courseCode,
            String courseName, int credits, String courseStatus, int semesterNumber,
            boolean isMandatory, String groupName, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record Page(List<CurriculumItem> items, int page, int size, long totalElements, long totalPages) {
    }

    private record ReferenceCount(String name, int count) {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
