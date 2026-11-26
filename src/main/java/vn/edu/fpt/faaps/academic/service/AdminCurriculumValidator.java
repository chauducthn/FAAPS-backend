package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.repository.CurriculumReadRepository;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.Conflict;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.CurriculumInput;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.CurriculumItem;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService.NotFound;

final class AdminCurriculumValidator {
    private final JdbcTemplate jdbc;
    private final CurriculumReadRepository reads;

    AdminCurriculumValidator(JdbcTemplate jdbc, CurriculumReadRepository reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    CurriculumItem lockCurriculum(UUID id) {
        List<UUID> ids = jdbc.query("SELECT id FROM curriculum_versions WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")), id.toString());
        if (ids.isEmpty()) {
            throw new NotFound("Curriculum or cohort not found");
        }
        return reads.detail(id);
    }

    void requireMajor(UUID id) {
        if (id == null || Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM majors WHERE id = ?)", Boolean.class, id.toString()))) {
            throw new NotFound("Major not found");
        }
    }

    void validateSemesters(UUID fromId, UUID toId) {
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
        List<LocalDate[]> toDates = jdbc.query("SELECT start_date, end_date FROM semesters WHERE id = ?",
                (rs, rowNum) -> new LocalDate[] {
                    rs.getDate("start_date").toLocalDate(),
                    rs.getDate("end_date") != null ? rs.getDate("end_date").toLocalDate() : null
                }, toId.toString());
        if (toDates.isEmpty()) {
            throw new NotFound("Effective-to semester not found");
        }
        LocalDate toStart = toDates.get(0)[0];
        LocalDate toEnd = toDates.get(0)[1];
        if (toStart.isBefore(fromDates.get(0))) {
            throw new IllegalArgumentException("effectiveToSemesterId cannot be before effectiveFromSemesterId");
        }
        if (toEnd != null && toEnd.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("End semester cannot be in the past");
        }
    }

    void lockMajors(List<UUID> majorIds) {
        majorIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(id -> {
            List<String> found = jdbc.query("SELECT id FROM majors WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString("id"), id.toString());
            if (found.isEmpty()) {
                throw new NotFound("Major not found");
            }
        });
    }

    void clearCurrent(UUID majorId, UUID exceptId) {
        if (exceptId == null) {
            jdbc.update("""
                    UPDATE curriculum_versions SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP
                    WHERE major_id = ? AND is_current = TRUE
                    """, majorId.toString());
        } else {
            jdbc.update("""
                    UPDATE curriculum_versions SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP
                    WHERE major_id = ? AND is_current = TRUE AND id <> ?
                    """, majorId.toString(), exceptId.toString());
        }
    }

    void deleteCurriculum(UUID id) {
        CurriculumItem curriculum = lockCurriculum(id);
        List<ReferenceCount> references = List.of(
                reference("students", "SELECT COUNT(*) FROM students WHERE curriculum_version_id = ?", id),
                reference("graduation checks",
                        "SELECT COUNT(*) FROM graduation_checks WHERE curriculum_version_id = ?", id),
                reference("curriculum courses",
                        "SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ?", id),
                reference("academic policies",
                        "SELECT COUNT(*) FROM academic_policies WHERE curriculum_version_id = ?", id),
                reference("graduation requirements",
                        "SELECT COUNT(*) FROM graduation_requirements WHERE curriculum_version_id = ?", id),
                reference("advisor assignments",
                        "SELECT COUNT(*) FROM cohort_advisors WHERE cohort_id = ?", id));
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

    void validateInput(CurriculumInput input) {
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

    String normalizeStatus(String status, String defaultValue) {
        if (status == null || status.isBlank()) {
            return defaultValue;
        }
        String normalized = status.strip().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
        }
        return normalized;
    }

    Object nullableUuid(UUID id) {
        return id == null ? null : id.toString();
    }

    private ReferenceCount reference(String name, String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id.toString());
        return new ReferenceCount(name, count == null ? 0 : count);
    }

    private record ReferenceCount(String name, int count) {
    }
}
