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

    public AdminCurriculumService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(UUID majorId, String status, String search, int page, int size) {
        validatePage(page, size);
        String normalizedStatus = normalizeOptionalStatus(status);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (majorId != null) {
            where.append(" AND cv.major_id = ?");
            params.add(majorId.toString());
        }
        if (normalizedStatus != null) {
            where.append(" AND cv.status = ?");
            params.add(normalizedStatus);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(cv.version) LIKE ? OR LOWER(cv.name) LIKE ? OR LOWER(m.code) LIKE ? OR LOWER(m.name) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id" + where,
                Long.class, params.toArray());
        long totalElements = count == null ? 0 : count;
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<CurriculumItem> items = jdbc.query(selectCurriculum() + where
                        + " ORDER BY m.code ASC, cv.version ASC, cv.id ASC LIMIT ? OFFSET ?",
                this::mapCurriculum, pageParams.toArray());
        long totalPages = (totalElements + size - 1) / size;
        return new Page(items, page, size, totalElements, totalPages);
    }

    public CurriculumItem detail(UUID id) {
        List<CurriculumItem> rows = jdbc.query(selectCurriculum() + " WHERE cv.id = ?",
                this::mapCurriculum, id.toString());
        if (rows.isEmpty()) {
            throw new NotFound("Curriculum or cohort not found");
        }
        return rows.get(0);
    }

    public List<CurriculumCourseItem> courses(UUID curriculumId) {
        detail(curriculumId);
        return jdbc.query("""
                SELECT cc.id, cc.curriculum_version_id, cc.course_id, c.code AS course_code,
                       c.name AS course_name, c.credits, c.status AS course_status,
                       cc.semester_number, COALESCE(cc.is_mandatory, TRUE) AS is_mandatory,
                       cc.group_name, cc.created_at, cc.updated_at
                FROM curriculum_courses cc
                JOIN courses c ON c.id = cc.course_id
                WHERE cc.curriculum_version_id = ?
                ORDER BY cc.semester_number ASC, c.code ASC, cc.id ASC
                """, this::mapCurriculumCourse, curriculumId.toString());
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

        CurriculumItem old = lockCurriculum(id);
        requireMajor(input.majorId());
        validateSemesters(input.effectiveFromSemesterId(), input.effectiveToSemesterId());
        lockMajors(List.of(old.majorId(), input.majorId()));
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

    private String selectCurriculum() {
        return """
                SELECT cv.id, cv.major_id, m.code AS major_code, m.name AS major_name,
                       cv.version, cv.name, cv.effective_from_semester_id,
                       sf.code AS effective_from_semester_code, sf.start_date AS effective_from_date,
                       cv.effective_to_semester_id, st.code AS effective_to_semester_code,
                       st.end_date AS effective_to_date, cv.description,
                       COALESCE(cv.is_current, FALSE) AS is_current, cv.status,
                       (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.curriculum_version_id = cv.id) AS course_count,
                       cv.created_at, cv.updated_at
                FROM curriculum_versions cv
                JOIN majors m ON m.id = cv.major_id
                JOIN semesters sf ON sf.id = cv.effective_from_semester_id
                LEFT JOIN semesters st ON st.id = cv.effective_to_semester_id
                """;
    }

    private CurriculumItem mapCurriculum(ResultSet rs, int rowNum) throws SQLException {
        return new CurriculumItem(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("major_id")), rs.getString("major_code"), rs.getString("major_name"),
                rs.getString("version"), rs.getString("name"),
                UUID.fromString(rs.getString("effective_from_semester_id")),
                rs.getString("effective_from_semester_code"), localDate(rs, "effective_from_date"),
                uuidOrNull(rs.getString("effective_to_semester_id")), rs.getString("effective_to_semester_code"),
                localDate(rs, "effective_to_date"), rs.getString("description"), rs.getBoolean("is_current"),
                rs.getString("status"), rs.getInt("course_count"), localDateTime(rs, "created_at"),
                localDateTime(rs, "updated_at"));
    }

    private CurriculumCourseItem mapCurriculumCourse(ResultSet rs, int rowNum) throws SQLException {
        return new CurriculumCourseItem(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("curriculum_version_id")), UUID.fromString(rs.getString("course_id")),
                rs.getString("course_code"), rs.getString("course_name"), rs.getInt("credits"),
                rs.getString("course_status"), rs.getInt("semester_number"), rs.getBoolean("is_mandatory"),
                rs.getString("group_name"), localDateTime(rs, "created_at"), localDateTime(rs, "updated_at"));
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

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return normalizeStatus(status, null);
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

    private UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        return rs.getDate(column) == null ? null : rs.getDate(column).toLocalDate();
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    public record CurriculumInput(UUID majorId, String version, String name, UUID effectiveFromSemesterId,
            UUID effectiveToSemesterId, String description, Boolean isCurrent, String status) {
    }

    public record CurriculumItem(UUID id, UUID majorId, String majorCode, String majorName, String version,
            String name, UUID effectiveFromSemesterId, String effectiveFromSemesterCode, LocalDate effectiveFromDate,
            UUID effectiveToSemesterId, String effectiveToSemesterCode, LocalDate effectiveToDate,
            String description, boolean isCurrent, String status, int courseCount,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
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
