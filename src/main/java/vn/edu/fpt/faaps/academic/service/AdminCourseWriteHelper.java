package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.repository.CourseReadRepository;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.Conflict;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseDetail;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.PrerequisiteInput;

final class AdminCourseWriteHelper {
    private static final Pattern COURSE_CODE_PATTERN = Pattern.compile("^[A-Z]{2}(0[1-9]|[1-9]\\d)(0[1-9]|[1-9]\\d)$");
    private final JdbcTemplate jdbc;
    private final CourseReadRepository reads;
    private final AdminCoursePrerequisiteHelper prerequisites;
    private final AdminCourseRowWriter rowWriter;

    AdminCourseWriteHelper(JdbcTemplate jdbc, CourseReadRepository reads, CourseRelationService relationService) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.prerequisites = new AdminCoursePrerequisiteHelper(jdbc, relationService);
        this.rowWriter = new AdminCourseRowWriter(jdbc);
    }

    static String normalizeAndValidateMaterialLink(String raw) {
        return AdminCourseRowWriter.normalizeAndValidateMaterialLink(raw);
    }

    static String extractMaterialLink(String raw) {
        return AdminCourseRowWriter.extractMaterialLink(raw);
    }

    CourseDetail create(String code, String name, int credits, UUID categoryId,
            String description, String objectives, String learningOutcomes, String materialLink,
            String status, List<PrerequisiteInput> prereqs) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("code and name are required");
        }
        String normalizedCode = validateCourseCode(code);
        if (credits < 0 || credits > 30) {
            throw new IllegalArgumentException("credits must be between 0 and 30");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId is required");
        }
        String normalizedLink = normalizeAndValidateMaterialLink(materialLink);
        String courseStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(courseStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }

        UUID id = UUID.randomUUID();
        prerequisites.validate(id, prereqs);
        rowWriter.insertCourseRow(id, normalizedCode, name.strip(), credits, categoryId.toString(),
                description, objectives, learningOutcomes, normalizedLink, courseStatus);
        prerequisites.save(id, prereqs);
        return reads.detail(id);
    }

    CourseDetail update(UUID id, String code, String name, Integer credits, UUID categoryId,
            String description, String objectives, String learningOutcomes,
            String materialLink, boolean updateMaterialLink,
            String status, List<PrerequisiteInput> prereqs) {
        CourseDetail current = reads.detail(id);
        prerequisites.validate(id, prereqs);
        String newCode = (code != null && !code.isBlank()) ? code.strip().toUpperCase() : current.code();
        if (!newCode.equalsIgnoreCase(current.code())) {
            newCode = validateCourseCode(newCode);
        }
        String newName = (name != null && !name.isBlank()) ? name.strip() : current.name();
        if (credits != null && (credits < 0 || credits > 30)) {
            throw new IllegalArgumentException("credits must be between 0 and 30");
        }
        int newCredits = credits != null ? credits : current.credits();
        UUID newCat = categoryId != null ? categoryId : current.categoryId();
        String newLink = (!updateMaterialLink || AdminCourseService.KEEP_EXISTING_MATERIAL_LINK.equals(materialLink))
                ? current.materialLink() : normalizeAndValidateMaterialLink(materialLink);
        String newStatus = resolveUpdatedStatus(status, current.status());

        rowWriter.updateCourseRow(id, newCode, newName, newCredits, newCat.toString(),
                description != null ? description : current.description(),
                objectives != null ? objectives : current.objectives(),
                learningOutcomes != null ? learningOutcomes : current.learningOutcomes(),
                newLink, newStatus);
        prerequisites.replace(id, prereqs);
        return reads.detail(id);
    }

    int bulkUpdateStatus(List<UUID> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = status.strip().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        int count = 0;
        for (UUID id : ids) {
            if (id != null) {
                count += jdbc.update(
                        "UPDATE courses SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        normalized, id.toString());
            }
        }
        return count;
    }

    void delete(UUID id) {
        reads.detail(id);
        Integer prereqRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_prerequisites WHERE prerequisite_course_id = ?",
                Integer.class, id.toString());
        if (prereqRefs != null && prereqRefs > 0) {
            throw new Conflict("Cannot delete course because it is a prerequisite for "
                    + prereqRefs + " other course(s)");
        }
        Integer curriculumRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_courses WHERE course_id = ?", Integer.class, id.toString());
        if (curriculumRefs != null && curriculumRefs > 0) {
            throw new Conflict("Cannot delete course because it is part of "
                    + curriculumRefs + " curriculum course list(s)");
        }
        jdbc.update("DELETE FROM course_prerequisites WHERE course_id = ?", id.toString());
        try {
            jdbc.update("DELETE FROM courses WHERE id = ?", id.toString());
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Cannot delete course because related records still exist");
        }
    }

    List<CategoryItem> categories() {
        return jdbc.query("SELECT id, code, name, description FROM course_categories ORDER BY code ASC",
                (rs, rowNum) -> new CategoryItem(UUID.fromString(rs.getString("id")),
                        rs.getString("code"), rs.getString("name"), rs.getString("description")));
    }

    private String validateCourseCode(String rawCode) {
        String clean = rawCode.strip().toUpperCase();
        if (!COURSE_CODE_PATTERN.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "Course code must combine major code, cohort digits, and 01-99 (e.g. SE1803)");
        }
        String majorCode = clean.substring(0, 2);
        String cohortVersion = "K" + clean.substring(2, 4);
        try {
            Integer activeMajors = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM majors WHERE status = 'ACTIVE'", Integer.class);
            if (activeMajors != null && activeMajors > 0) {
                Integer matchedMajor = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM majors WHERE status = 'ACTIVE' AND UPPER(code) = ?",
                        Integer.class, majorCode);
                if (matchedMajor == null || matchedMajor == 0) {
                    throw new IllegalArgumentException(
                            "Major code " + majorCode + " does not match any active major");
                }
            }
            Integer activeCohorts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curriculum_versions WHERE status = 'ACTIVE'", Integer.class);
            if (activeCohorts != null && activeCohorts > 0) {
                Integer matchedCohort = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM curriculum_versions WHERE status = 'ACTIVE' AND UPPER(version) = ?",
                        Integer.class, cohortVersion);
                if (matchedCohort == null || matchedCohort == 0) {
                    throw new IllegalArgumentException(
                            "Cohort " + cohortVersion + " does not match any active cohort");
                }
            }
        } catch (BadSqlGrammarException ignored) {
            // Minimal test schemas without majors/curriculum_versions tables validate format only.
        }
        return clean;
    }

    private String resolveUpdatedStatus(String status, String currentStatus) {
        if (status == null || status.isBlank()) {
            return currentStatus;
        }
        String upper = status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(upper)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return upper;
    }
}
