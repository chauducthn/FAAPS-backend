package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.repository.CurriculumReadRepository;

@Service
public class AdminCurriculumService {
    private final JdbcTemplate jdbc;
    private final CurriculumReadRepository reads;
    private final AdminCurriculumValidator validator;
    private final AdminCurriculumCourseHelper courseHelper;

    public AdminCurriculumService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reads = new CurriculumReadRepository(jdbc);
        this.validator = new AdminCurriculumValidator(jdbc, reads);
        this.courseHelper = new AdminCurriculumCourseHelper(jdbc, reads, validator);
    }

    public Page list(UUID majorId, String status, Boolean isCurrent, String search, int page, int size) {
        return reads.list(majorId, null, null, null, status, isCurrent, search, page, size);
    }

    public Page list(UUID majorId, String majorCode, String version, UUID startSemesterId,
            String status, Boolean isCurrent, String search, int page, int size) {
        return reads.list(majorId, majorCode, version, startSemesterId, status, isCurrent, search, page, size);
    }

    public CurriculumItem detail(UUID id) {
        return reads.detail(id);
    }

    public List<CurriculumCourseItem> courses(UUID curriculumId) {
        return reads.courses(curriculumId);
    }

    @Transactional
    public CurriculumItem create(CurriculumInput input) {
        validator.validateInput(input);
        String status = validator.normalizeStatus(input.status(), "ACTIVE");
        boolean current = Boolean.TRUE.equals(input.isCurrent());
        if (current && !"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("An inactive curriculum cannot be current");
        }
        validator.requireMajor(input.majorId());
        validator.validateSemesters(input.effectiveFromSemesterId(), input.effectiveToSemesterId());
        validator.lockMajors(List.of(input.majorId()));

        UUID id = UUID.randomUUID();
        try {
            if (current) {
                validator.clearCurrent(input.majorId(), null);
            }
            jdbc.update("""
                    INSERT INTO curriculum_versions
                        (id, major_id, version, name, effective_from_semester_id,
                         effective_to_semester_id, description, is_current, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, id.toString(), input.majorId().toString(), input.version().strip(), input.name().strip(),
                    input.effectiveFromSemesterId().toString(), validator.nullableUuid(input.effectiveToSemesterId()),
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
        validator.validateInput(input);
        String status = validator.normalizeStatus(input.status(), null);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        boolean current = Boolean.TRUE.equals(input.isCurrent());
        if (current && !"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("An inactive curriculum cannot be current");
        }

        CurriculumItem observed = detail(id);
        validator.requireMajor(input.majorId());
        validator.validateSemesters(input.effectiveFromSemesterId(), input.effectiveToSemesterId());
        validator.lockMajors(List.of(observed.majorId(), input.majorId()));
        CurriculumItem old = validator.lockCurriculum(id);
        if (!old.majorId().equals(observed.majorId())) {
            throw new Conflict("Curriculum changed while this update was being prepared; retry the request");
        }
        try {
            if (current) {
                validator.clearCurrent(input.majorId(), id);
            }
            jdbc.update("""
                    UPDATE curriculum_versions
                    SET major_id = ?, version = ?, name = ?, effective_from_semester_id = ?,
                        effective_to_semester_id = ?, description = ?, is_current = ?, status = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, input.majorId().toString(), input.version().strip(), input.name().strip(),
                    input.effectiveFromSemesterId().toString(), validator.nullableUuid(input.effectiveToSemesterId()),
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
        String status = validator.normalizeStatus(requestedStatus, null);
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        CurriculumItem current = validator.lockCurriculum(id);
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
        validator.deleteCurriculum(id);
    }

    @Transactional
    public List<CurriculumCourseItem> addCourses(UUID curriculumId, List<UUID> courseIds,
            Integer semesterNumber, Boolean mandatory) {
        return courseHelper.addCourses(curriculumId, courseIds, semesterNumber, mandatory);
    }

    @Transactional
    public CurriculumCourseItem updateCourse(UUID curriculumId, UUID courseId,
            Integer semesterNumber, Boolean mandatory, String groupName) {
        return courseHelper.updateCourse(curriculumId, courseId, semesterNumber, mandatory, groupName);
    }

    @Transactional
    public void removeCourse(UUID curriculumId, UUID courseId) {
        courseHelper.removeCourse(curriculumId, courseId);
    }

    public record CurriculumInput(UUID majorId, String version, String name, UUID effectiveFromSemesterId,
            UUID effectiveToSemesterId, String description, Boolean isCurrent, String status) {}

    public record CurriculumItem(UUID id, UUID majorId, String majorCode, String majorName, String version,
            String name, UUID effectiveFromSemesterId, String effectiveFromSemesterCode, LocalDate effectiveFromDate,
            UUID effectiveToSemesterId, String effectiveToSemesterCode, LocalDate effectiveToDate,
            String description, boolean isCurrent, String status, int courseCount,
            int totalCredits, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record CurriculumCourseItem(UUID id, UUID curriculumId, UUID courseId, String courseCode,
            String courseName, int credits, String courseStatus, int semesterNumber,
            boolean isMandatory, String groupName, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record Page(List<CurriculumItem> items, int page, int size, long totalElements, long totalPages) {}

    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
