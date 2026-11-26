package vn.edu.fpt.faaps.academic.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportConfirmItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportPreviewResult;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportRowItem;
import vn.edu.fpt.faaps.academic.service.CourseImportSheetParser.RawCourseRow;

final class CourseImportService {
    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final CourseImportValidator validator;

    CourseImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.validator = new CourseImportValidator(jdbc);
    }

    public ImportPreviewResult previewXlsx(InputStream inputStream, List<CategoryItem> cats) {
        Map<String, UUID> catMap = new HashMap<>();
        for (CategoryItem cat : cats) {
            catMap.put(cat.code().toUpperCase(), cat.id());
            catMap.put(cat.name().toUpperCase(), cat.id());
        }
        Set<String> existingCodesInDb = validator.loadExistingCourseCodes();
        List<ImportRowItem> rows = new ArrayList<>();
        Set<String> seenCodesInFile = new HashSet<>();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            List<RawCourseRow> parsedRows = CourseImportSheetParser.parseSheet(workbook.getSheetAt(0));
            for (RawCourseRow raw : parsedRows) {
                List<String> errors = validator.validateRow(
                        raw.code(), raw.name(), raw.creditsStr(), raw.catStr(),
                        raw.statusStr(), raw.materialLink(), seenCodesInFile, existingCodesInDb, cats, catMap);
                int credits = validator.parseCredits(raw.creditsStr());
                UUID categoryId = validator.resolveCategoryId(raw.catStr(), cats, catMap);
                String courseStatus = validator.normalizeStatus(raw.statusStr());
                String link = raw.materialLink() != null && !raw.materialLink().isBlank() ? raw.materialLink().strip() : null;
                String status = errors.isEmpty() ? "VALID" : "ERROR";
                rows.add(new ImportRowItem(raw.rowNumber(), raw.code().strip(), raw.name(), credits,
                        categoryId != null ? categoryId.toString() : null, raw.catStr(), raw.description(),
                        raw.prerequisites(), raw.objectives(), raw.learningOutcomes(), courseStatus, link, status, errors));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read Excel file: " + e.getMessage());
        }

        int validCount = (int) rows.stream().filter(i -> i.status().equals("VALID")).count();
        return new ImportPreviewResult(rows.size(), validCount, rows.size() - validCount, rows);
    }

    public int confirmImport(List<ImportConfirmItem> items, List<CategoryItem> cats) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        UUID defaultCatId = cats.isEmpty() ? null : cats.get(0).id();
        Map<String, String> codeToId = new HashMap<>();
        jdbc.query("SELECT UPPER(code), id FROM courses", rs -> {
            codeToId.put(rs.getString(1), rs.getString(2));
        });
        List<PendingCourse> pending = batchUpsertCourses(items, defaultCatId, codeToId);
        batchLinkPrerequisites(pending, codeToId);
        return pending.size();
    }

    private List<PendingCourse> batchUpsertCourses(
            List<ImportConfirmItem> items, UUID defaultCatId, Map<String, String> codeToId) {
        List<PendingCourse> pending = new ArrayList<>();
        List<Object[]> insertArgs = new ArrayList<>();
        for (ImportConfirmItem item : items) {
            if (item.code() == null || item.code().isBlank() || item.name() == null || item.name().isBlank()) {
                continue;
            }
            UUID catId = item.categoryId() != null ? item.categoryId() : defaultCatId;
            if (catId == null) {
                continue;
            }
            String code = item.code().strip();
            String upper = code.toUpperCase();
            String existingId = codeToId.get(upper);
            UUID courseId;
            if (existingId == null) {
                courseId = UUID.randomUUID();
                String courseStatus = validator.normalizeStatus(item.status());
                String link = AdminCourseWriteHelper.normalizeAndValidateMaterialLink(item.materialLink());
                insertArgs.add(new Object[] {
                    courseId.toString(), code, item.name().strip(),
                    item.credits() >= 0 ? item.credits() : 3, catId.toString(),
                    item.description(), item.objectives(), item.learningOutcomes(), link, courseStatus
                });
                codeToId.put(upper, courseId.toString());
            } else {
                courseId = UUID.fromString(existingId);
            }
            pending.add(new PendingCourse(courseId, item));
        }
        String sql = """
                INSERT INTO courses (id, code, name, credits, category_id, description, objectives,
                    learning_outcomes, material_link, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        for (int i = 0; i < insertArgs.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, insertArgs.subList(i, Math.min(i + BATCH_SIZE, insertArgs.size())));
        }
        return pending;
    }

    private void batchLinkPrerequisites(List<PendingCourse> pending, Map<String, String> codeToId) {
        Map<String, Set<String>> graph = validator.loadPrerequisiteGraph();
        List<Object[]> insertArgs = new ArrayList<>();
        for (PendingCourse pc : pending) {
            String prereqs = pc.item().prerequisites();
            if (prereqs == null || prereqs.isBlank()) {
                continue;
            }
            String cid = pc.id().toString();
            for (String part : prereqs.split("[,;]")) {
                String prereqCode = part.strip().toUpperCase();
                String prereqId = prereqCode.isBlank() ? null : codeToId.get(prereqCode);
                if (prereqId == null || prereqId.equals(cid)) {
                    continue;
                }
                Set<String> edges = graph.computeIfAbsent(cid, k -> new HashSet<>());
                if (edges.contains(prereqId)) {
                    continue;
                }
                if (validator.createsCycleInGraph(graph, cid, prereqId)) {
                    throw new AdminCourseService.Conflict("Circular course prerequisite detected");
                }
                edges.add(prereqId);
                insertArgs.add(new Object[] { UUID.randomUUID().toString(), cid, prereqId });
            }
        }
        String sql = """
                INSERT INTO course_prerequisites
                    (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                VALUES (?, ?, ?, 1, 'PREREQUISITE', 5.00, CURRENT_TIMESTAMP)
                """;
        for (int i = 0; i < insertArgs.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, insertArgs.subList(i, Math.min(i + BATCH_SIZE, insertArgs.size())));
        }
    }

    private record PendingCourse(UUID id, ImportConfirmItem item) {}
}
