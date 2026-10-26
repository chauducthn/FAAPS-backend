package vn.edu.fpt.faaps.admin;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCourseService {
    private final JdbcTemplate jdbc;
    private final CourseReadRepository reads;
    private final CourseImportService importService;
    private final CourseRelationService relationService;

    public AdminCourseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reads = new CourseReadRepository(jdbc);
        this.importService = new CourseImportService(jdbc);
        this.relationService = new CourseRelationService(jdbc, reads);
    }

    public Page list(String search, String status, UUID categoryId, int page, int size, String sort) {
        return reads.list(search, status, categoryId, page, size, sort);
    }

    public CourseDetail detail(UUID id) {
        return reads.detail(id);
    }

    public List<CourseRelation> relations(UUID courseId) {
        return relationService.list(courseId);
    }

    @Transactional
    public CourseRelation addRelation(UUID courseId, String kind, Integer groupNo,
            UUID otherCourseId, BigDecimal minimumScore) {
        return relationService.add(courseId,kind,groupNo,otherCourseId,minimumScore);
    }

    @Transactional
    public CourseRelation updateRelation(UUID courseId, UUID relationId,
            Integer groupNo, BigDecimal minimumScore) {
        return relationService.update(courseId,relationId,groupNo,minimumScore);
    }

    @Transactional
    public CourseRelation deleteRelation(UUID courseId, UUID relationId) {
        return relationService.delete(courseId,relationId);
    }

    @Transactional
    public CourseDetail create(String code, String name, int credits, UUID categoryId,
            String description, String objectives, String learningOutcomes,
            String status, List<PrerequisiteInput> prerequisites) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("code and name are required");
        }
        if (credits <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId is required");
        }
        String courseStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(courseStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }

        UUID id = UUID.randomUUID();
        validatePrerequisites(id, prerequisites);
        try {
            jdbc.update("""
                    INSERT INTO courses (id, code, name, credits, category_id, description, objectives, learning_outcomes, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    id.toString(), code.strip().toUpperCase(), name.strip(), credits, categoryId.toString(),
                    description, objectives, learningOutcomes, courseStatus);
        } catch (DuplicateKeyException e) {
            throw new Conflict("Course code already exists");
        }

        savePrerequisites(id, prerequisites);
        return detail(id);
    }

    @Transactional
    public CourseDetail update(UUID id, String code, String name, Integer credits, UUID categoryId,
            String description, String objectives, String learningOutcomes,
            String status, List<PrerequisiteInput> prerequisites) {
        CourseDetail current = detail(id);
        validatePrerequisites(id, prerequisites);
        String newCode = (code != null && !code.isBlank()) ? code.strip().toUpperCase() : current.code();
        String newName = (name != null && !name.isBlank()) ? name.strip() : current.name();
        int newCredits = (credits != null && credits > 0) ? credits : current.credits();
        UUID newCat = categoryId != null ? categoryId : current.categoryId();
        String newDesc = description != null ? description : current.description();
        String newObj = objectives != null ? objectives : current.objectives();
        String newLo = learningOutcomes != null ? learningOutcomes : current.learningOutcomes();
        String newStatus = current.status();
        if (status != null && !status.isBlank()) {
            String upper = status.toUpperCase();
            if (List.of("ACTIVE", "INACTIVE").contains(upper)) {
                newStatus = upper;
            }
        }

        try {
            jdbc.update("""
                    UPDATE courses
                    SET code = ?, name = ?, credits = ?, category_id = ?, description = ?,
                        objectives = ?, learning_outcomes = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    newCode, newName, newCredits, newCat.toString(), newDesc, newObj, newLo, newStatus, id.toString());
        } catch (DuplicateKeyException e) {
            throw new Conflict("Course code already exists");
        }

        if (prerequisites != null) {
            jdbc.update("DELETE FROM course_prerequisites WHERE course_id = ?", id.toString());
            savePrerequisites(id, prerequisites);
        }

        return detail(id);
    }

    @Transactional
    public void delete(UUID id) {
        detail(id);
        Integer prereqRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_prerequisites WHERE prerequisite_course_id = ?", Integer.class, id.toString());
        if (prereqRefs != null && prereqRefs > 0) {
            throw new Conflict("Cannot delete course because it is a prerequisite for " + prereqRefs + " other course(s)");
        }

        Integer curriculumRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_courses WHERE course_id = ?", Integer.class, id.toString());
        if (curriculumRefs != null && curriculumRefs > 0) {
            throw new Conflict("Cannot delete course because it is part of " + curriculumRefs + " curriculum course list(s)");
        }

        jdbc.update("DELETE FROM course_prerequisites WHERE course_id = ?", id.toString());
        try {
            jdbc.update("DELETE FROM courses WHERE id = ?", id.toString());
        } catch (DataIntegrityViolationException exception) {
            throw new Conflict("Cannot delete course because related records still exist");
        }
    }

    @Transactional
    public int bulkDelete(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UUID id : ids) {
            delete(id);
            count++;
        }
        return count;
    }

    public List<CategoryItem> categories() {
        return jdbc.query("SELECT id, code, name, description FROM course_categories ORDER BY code ASC",
                (rs, rowNum) -> new CategoryItem(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description")
                ));
    }

    public ImportPreviewResult previewXlsx(InputStream inputStream) {
        return importService.previewXlsx(inputStream, categories());
    }

    @Transactional
    public int confirmImport(List<ImportConfirmItem> items) {
        return importService.confirmImport(items, categories());
    }

    private void validatePrerequisites(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites == null) {
            return;
        }
        for (PrerequisiteInput relation : prerequisites) {
            if (relation == null || relation.prerequisiteCourseId() == null
                    || relation.prerequisiteCourseId().equals(courseId)) {
                throw new IllegalArgumentException("Related course must be different from this course");
            }
            String type = relation.type() == null || relation.type().isBlank()
                    ? "PREREQUISITE" : relation.type().strip().toUpperCase();
            if (!List.of("PREREQUISITE", "REPLACEMENT").contains(type)) {
                throw new IllegalArgumentException("Unsupported course relation type: " + type);
            }
        }
    }

    private void savePrerequisites(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites == null || prerequisites.isEmpty()) {
            return;
        }
        for (PrerequisiteInput p : prerequisites) {
            UUID pid = UUID.randomUUID();
            String type = (p.type() != null && !p.type().isBlank()) ? p.type().strip().toUpperCase() : "PREREQUISITE";
            int group = p.groupNo() != null && p.groupNo() > 0 ? p.groupNo() : 1;
            BigDecimal minScore = type.equals("REPLACEMENT") ? null
                    : p.minimumScore() != null ? p.minimumScore() : new BigDecimal("5.00");
            try {
                jdbc.update("""
                        INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        pid.toString(), courseId.toString(), p.prerequisiteCourseId().toString(), group, type, minScore);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not save course relation", exception);
            }
        }
    }

    public record CourseSummary(UUID id, String code, String name, int credits, UUID categoryId,
            String categoryCode, String categoryName, String description, String status, int prerequisiteCount,
            int usedInCurriculumCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CourseDetail(UUID id, String code, String name, int credits, UUID categoryId,
            String categoryCode, String categoryName, String description, String objectives,
            String learningOutcomes, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            List<PrerequisiteItem> prerequisites, List<CourseReference> referencedBy,
            int usedInCurriculumCount) {
    }

    public record CourseReference(UUID courseId, String courseCode, String courseName) {
    }

    public record PrerequisiteItem(UUID id, UUID prerequisiteCourseId, String prerequisiteCode,
            String prerequisiteName, int groupNo, String type, BigDecimal minimumScore) {
    }

    public record CourseRelation(UUID relationId, String kind, int groupNo, UUID otherCourseId,
            String otherCourseCode, String otherCourseName, BigDecimal minimumScore) {
    }

    public record PrerequisiteInput(UUID prerequisiteCourseId, Integer groupNo, String type, BigDecimal minimumScore) {
    }

    public record CategoryItem(UUID id, String code, String name, String description) {
    }

    public record ImportRowItem(int rowNumber, String code, String name, int credits, String categoryId,
            String categoryName, String description, String prerequisites, String objectives, String learningOutcomes,
            String status, List<String> errors) {
        public ImportRowItem(int rowNumber, String code, String name, int credits, String categoryId,
                String categoryName, String description, String prerequisites, String status, List<String> errors) {
            this(rowNumber, code, name, credits, categoryId, categoryName, description, prerequisites, null, null, status, errors);
        }
    }

    public record ImportPreviewResult(int totalRows, int validRows, int errorRows, List<ImportRowItem> items) {
    }

    public record ImportConfirmItem(String code, String name, int credits, UUID categoryId, String description,
            String prerequisites, String objectives, String learningOutcomes) {
        public ImportConfirmItem(String code, String name, int credits, UUID categoryId, String description) {
            this(code, name, credits, categoryId, description, null, null, null);
        }

        public ImportConfirmItem(String code, String name, int credits, UUID categoryId, String description, String prerequisites) {
            this(code, name, credits, categoryId, description, prerequisites, null, null);
        }
    }

    public record Page(List<CourseSummary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
        public NotFound() {
            super("Course not found");
        }

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
