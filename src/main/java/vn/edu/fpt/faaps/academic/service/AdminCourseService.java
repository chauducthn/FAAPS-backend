package vn.edu.fpt.faaps.academic.service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.repository.CourseReadRepository;

@Service
public class AdminCourseService {
    public static final String KEEP_EXISTING_MATERIAL_LINK = "__KEEP_EXISTING_MATERIAL_LINK__";

    private final CourseReadRepository reads;
    private final CourseImportService importService;
    private final CourseRelationService relationService;
    private final AdminCourseWriteHelper writeHelper;

    public AdminCourseService(JdbcTemplate jdbc) {
        this.reads = new CourseReadRepository(jdbc);
        this.importService = new CourseImportService(jdbc);
        this.relationService = new CourseRelationService(jdbc, reads);
        this.writeHelper = new AdminCourseWriteHelper(jdbc, reads, relationService);
    }

    public Page list(String search, String status, UUID categoryId, int page, int size, String sort) {
        return reads.list(search, status, categoryId, null, page, size, sort);
    }

    public Page list(String search, String status, UUID categoryId, Integer credits, int page, int size, String sort) {
        return reads.list(search, status, categoryId, credits, page, size, sort);
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
        return relationService.add(courseId, kind, groupNo, otherCourseId, minimumScore);
    }

    @Transactional
    public CourseRelation updateRelation(UUID courseId, UUID relationId, Integer groupNo, BigDecimal minimumScore) {
        return relationService.update(courseId, relationId, groupNo, minimumScore);
    }

    @Transactional
    public CourseRelation deleteRelation(UUID courseId, UUID relationId) {
        return relationService.delete(courseId, relationId);
    }

    @Transactional
    public CourseDetail create(String code, String name, int credits, UUID categoryId, String description,
            String objectives, String learningOutcomes, String status, List<PrerequisiteInput> prerequisites) {
        return create(code, name, credits, categoryId, description, objectives,
                learningOutcomes, null, status, prerequisites);
    }

    @Transactional
    public CourseDetail create(String code, String name, int credits, UUID categoryId, String description,
            String objectives, String learningOutcomes, String materialLink,
            String status, List<PrerequisiteInput> prerequisites) {
        return writeHelper.create(code, name, credits, categoryId, description, objectives,
                learningOutcomes, materialLink, status, prerequisites);
    }

    @Transactional
    public CourseDetail update(UUID id, String code, String name, Integer credits, UUID categoryId, String description,
            String objectives, String learningOutcomes, String status, List<PrerequisiteInput> prerequisites) {
        return update(id, code, name, credits, categoryId, description, objectives, learningOutcomes,
                KEEP_EXISTING_MATERIAL_LINK, false, status, prerequisites);
    }

    @Transactional
    public CourseDetail update(UUID id, String code, String name, Integer credits, UUID categoryId, String description,
            String objectives, String learningOutcomes, String materialLink, boolean updateMaterialLink,
            String status, List<PrerequisiteInput> prerequisites) {
        return writeHelper.update(id, code, name, credits, categoryId, description, objectives,
                learningOutcomes, materialLink, updateMaterialLink, status, prerequisites);
    }

    @Transactional
    public int bulkUpdateStatus(List<UUID> ids, String status) {
        return writeHelper.bulkUpdateStatus(ids, status);
    }

    @Transactional
    public void delete(UUID id) {
        writeHelper.delete(id);
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
        return writeHelper.categories();
    }

    public ImportPreviewResult previewXlsx(InputStream inputStream) {
        return importService.previewXlsx(inputStream, categories());
    }

    @Transactional
    public int confirmImport(List<ImportConfirmItem> items) {
        return importService.confirmImport(items, categories());
    }

    public static String normalizeAndValidateMaterialLink(String raw) {
        return AdminCourseWriteHelper.normalizeAndValidateMaterialLink(raw);
    }

    public static String extractMaterialLink(String raw) {
        return AdminCourseWriteHelper.extractMaterialLink(raw);
    }

    public record CourseSummary(UUID id, String code, String name, int credits, UUID categoryId,
            String categoryCode, String categoryName, String description, String materialLink, String status,
            int prerequisiteCount, int usedInCurriculumCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public CourseSummary(UUID id, String code, String name, int credits, UUID categoryId, String categoryCode,
                String categoryName, String description, String status, int prerequisiteCount,
                int usedInCurriculumCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, code, name, credits, categoryId, categoryCode, categoryName, description, null,
                    status, prerequisiteCount, usedInCurriculumCount, createdAt, updatedAt);
        }
    }

    public record CourseDetail(UUID id, String code, String name, int credits, UUID categoryId, String categoryCode,
            String categoryName, String description, String objectives, String learningOutcomes, String materialLink,
            String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            List<PrerequisiteItem> prerequisites, List<CourseReference> referencedBy, int usedInCurriculumCount) {
        public CourseDetail(UUID id, String code, String name, int credits, UUID categoryId, String categoryCode,
                String categoryName, String description, String objectives, String learningOutcomes, String status,
                LocalDateTime createdAt, LocalDateTime updatedAt, List<PrerequisiteItem> prerequisites,
                List<CourseReference> referencedBy, int usedInCurriculumCount) {
            this(id, code, name, credits, categoryId, categoryCode, categoryName, description, objectives,
                    learningOutcomes, null, status, createdAt, updatedAt,
                    prerequisites, referencedBy, usedInCurriculumCount);
        }
    }

    public record CourseReference(UUID courseId, String courseCode, String courseName) {}
    public record PrerequisiteItem(UUID id, UUID prerequisiteCourseId, String prerequisiteCode,
            String prerequisiteName, int groupNo, String type, BigDecimal minimumScore) {}
    public record CourseRelation(UUID relationId, String kind, int groupNo, UUID otherCourseId,
            String otherCourseCode, String otherCourseName, BigDecimal minimumScore) {}
    public record PrerequisiteInput(UUID prerequisiteCourseId, Integer groupNo, String type, BigDecimal minimumScore) {}
    public record CategoryItem(UUID id, String code, String name, String description) {}
    public record ImportRowItem(int rowNumber, String code, String name, int credits, String categoryId,
            String categoryName, String description, String prerequisites, String objectives, String learningOutcomes,
            String status, List<String> errors) {
        public ImportRowItem(int rowNumber, String code, String name, int credits, String categoryId,
                String categoryName, String description, String prerequisites, String status, List<String> errors) {
            this(rowNumber, code, name, credits, categoryId, categoryName, description, prerequisites,
                    null, null, status, errors);
        }
    }
    public record ImportPreviewResult(int totalRows, int validRows, int errorRows, List<ImportRowItem> items) {}
    public record ImportConfirmItem(String code, String name, int credits, UUID categoryId, String description,
            String prerequisites, String objectives, String learningOutcomes) {
        public ImportConfirmItem(String code, String name, int credits, UUID categoryId, String description) {
            this(code, name, credits, categoryId, description, null, null, null);
        }
        public ImportConfirmItem(String code, String name, int credits, UUID categoryId,
                String description, String prerequisites) {
            this(code, name, credits, categoryId, description, prerequisites, null, null);
        }
    }
    public record Page(List<CourseSummary> items, int page, int size, long totalElements, long totalPages) {}
    public static class NotFound extends RuntimeException {
        public NotFound() { super("Course not found"); }
        public NotFound(String message) { super(message); }
    }
    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
