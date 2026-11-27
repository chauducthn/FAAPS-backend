package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.repository.CourseReadRepository;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.Conflict;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseDetail;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.PrerequisiteInput;

final class AdminCourseWriteHelper {
    private static final String CODE_PART = "(?:[\\p{Lu}\\d][\\p{L}]{2}\\d{2}[\\dx][\\p{Ll}]?(?:[_-][\\p{L}0-9.*]+)*\\*?|"
            + "[\\p{Lu}\\d][\\p{L}]{1,5}\\d{0,2}(?:[_-][\\p{L}0-9.*]+)*(?:\\*\\.?\\d+(?:\\.\\d+)?)?\\*?)";
    private static final Pattern COURSE_CODE_PATTERN = Pattern.compile("^" + CODE_PART + "(?:/" + CODE_PART + ")*$");
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
            String workloadLevel, String status, List<PrerequisiteInput> prereqs) {
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
        String normalizedWorkload = CourseWorkload.normalizeManualLevel(workloadLevel);
        String courseStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(courseStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }

        UUID id = UUID.randomUUID();
        prerequisites.validate(id, prereqs);
        rowWriter.insertCourseRow(id, normalizedCode, name.strip(), credits, categoryId.toString(),
                description, objectives, learningOutcomes, normalizedLink, normalizedWorkload, courseStatus);
        prerequisites.save(id, prereqs);
        return reads.detail(id);
    }

    CourseDetail update(UUID id, String code, String name, Integer credits, UUID categoryId,
            String description, String objectives, String learningOutcomes,
            String materialLink, boolean updateMaterialLink, String workloadLevel, boolean updateWorkloadLevel,
            String status, List<PrerequisiteInput> prereqs) {
        CourseDetail current = reads.detail(id);
        prerequisites.validate(id, prereqs);
        String newCode = (code != null && !code.isBlank()) ? code.strip() : current.code();
        if (!newCode.equals(current.code())) {
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
        String currentManual = current.workload() != null ? current.workload().manualLevel() : null;
        String newWorkload = (!updateWorkloadLevel
                || AdminCourseService.KEEP_EXISTING_WORKLOAD_LEVEL.equals(workloadLevel))
                ? currentManual : CourseWorkload.normalizeManualLevel(workloadLevel);
        String newStatus = resolveUpdatedStatus(status, current.status());

        rowWriter.updateCourseRow(id, newCode, newName, newCredits, newCat.toString(),
                description != null ? description : current.description(),
                objectives != null ? objectives : current.objectives(),
                learningOutcomes != null ? learningOutcomes : current.learningOutcomes(),
                newLink, newWorkload, newStatus);
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
        String clean = rawCode.strip();
        if (!COURSE_CODE_PATTERN.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "Invalid course code format (e.g. CSD201, SSL101c, PEN, PHE_COM*1, TMI_ELE)");
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
