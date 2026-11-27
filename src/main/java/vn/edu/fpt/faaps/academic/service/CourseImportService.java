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
        ensureEnrichmentColumns();
    }

    private void ensureEnrichmentColumns() {
        try { jdbc.execute("ALTER TABLE courses ADD COLUMN knowledge_block VARCHAR(255) NULL"); }
        catch (RuntimeException ignored) { /* Column exists or minimal schema. */ }
        try { jdbc.execute("ALTER TABLE courses ADD COLUMN grading_structure TEXT NULL"); }
        catch (RuntimeException ignored) { /* Column exists or minimal schema. */ }
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
                boolean hasEnrich = notBlank(raw.description()) || notBlank(raw.objectives())
                        || notBlank(raw.learningOutcomes()) || notBlank(raw.knowledgeBlock())
                        || notBlank(raw.gradingStructure());
                List<String> errors = validator.validateRow(
                        raw.code(), raw.name(), raw.creditsStr(), raw.catStr(),
                        raw.statusStr(), raw.materialLink(), hasEnrich, seenCodesInFile, existingCodesInDb, cats, catMap);
                int credits = validator.parseCredits(raw.creditsStr());
                UUID categoryId = validator.resolveCategoryId(raw.catStr(), cats, catMap);
                String courseStatus = validator.normalizeStatus(raw.statusStr());
                String link = notBlank(raw.materialLink()) ? raw.materialLink().strip() : null;
                String status = errors.isEmpty() ? "VALID" : "ERROR";
                rows.add(new ImportRowItem(raw.rowNumber(), raw.code().strip(), raw.name(), credits,
                        categoryId != null ? categoryId.toString() : null, raw.catStr(), raw.description(),
                        raw.prerequisites(), raw.objectives(), raw.learningOutcomes(), courseStatus, link,
                        blankToNull(raw.knowledgeBlock()), blankToNull(raw.gradingStructure()), status, errors));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read Excel file: " + e.getMessage());
        }

        int validCount = (int) rows.stream().filter(i -> i.status().equals("VALID")).count();
        return new ImportPreviewResult(rows.size(), validCount, rows.size() - validCount, rows);
    }

    public int confirmImport(List<ImportConfirmItem> items, List<CategoryItem> cats) {
        if (items == null || items.isEmpty()) return 0;
        UUID defaultCatId = cats.isEmpty() ? null : cats.get(0).id();
        Map<String, String> codeToId = new HashMap<>();
        jdbc.query("SELECT UPPER(code), id FROM courses", rs -> { codeToId.put(rs.getString(1), rs.getString(2)); });
        List<PendingCourse> pending = batchUpsertCourses(items, defaultCatId, codeToId);
        batchLinkPrerequisites(pending, codeToId);
        return pending.size();
    }

    private List<PendingCourse> batchUpsertCourses(
            List<ImportConfirmItem> items, UUID defaultCatId, Map<String, String> codeToId) {
        List<PendingCourse> pending = new ArrayList<>();
        List<Object[]> insertArgs = new ArrayList<>();
        List<Object[]> updateArgs = new ArrayList<>();
        for (ImportConfirmItem item : items) {
            if (!notBlank(item.code()) || !notBlank(item.name())) continue;
            UUID catId = item.categoryId() != null ? item.categoryId() : defaultCatId;
            if (catId == null) continue;
            String code = item.code().strip();
            String upper = code.toUpperCase();
            String existingId = codeToId.get(upper);
            String link = AdminCourseWriteHelper.normalizeAndValidateMaterialLink(item.materialLink());
            String kb = truncate(blankToNull(item.knowledgeBlock()), 255);
            String gs = blankToNull(item.gradingStructure());
            UUID courseId;
            if (existingId == null) {
                courseId = UUID.randomUUID();
                insertArgs.add(new Object[] {
                    courseId.toString(), code, item.name().strip(),
                    item.credits() >= 0 ? item.credits() : 3, catId.toString(),
                    blankToNull(item.description()), blankToNull(item.objectives()), blankToNull(item.learningOutcomes()),
                    link, kb, gs, validator.normalizeStatus(item.status())
                });
                codeToId.put(upper, courseId.toString());
            } else {
                courseId = UUID.fromString(existingId);
                updateArgs.add(new Object[] {
                    blankToNull(item.description()), blankToNull(item.objectives()), blankToNull(item.learningOutcomes()),
                    link, kb, gs, existingId
                });
            }
            pending.add(new PendingCourse(courseId, item));
        }
        String insertSql = """
                INSERT INTO courses (id, code, name, credits, category_id, description, objectives,
                    learning_outcomes, material_link, knowledge_block, grading_structure, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        for (int i = 0; i < insertArgs.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(insertSql, insertArgs.subList(i, Math.min(i + BATCH_SIZE, insertArgs.size())));
        }
        String updateSql = """
                UPDATE courses
                SET description = COALESCE(?, description), objectives = COALESCE(?, objectives),
                    learning_outcomes = COALESCE(?, learning_outcomes), material_link = COALESCE(?, material_link),
                    knowledge_block = COALESCE(?, knowledge_block), grading_structure = COALESCE(?, grading_structure),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        for (int i = 0; i < updateArgs.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(updateSql, updateArgs.subList(i, Math.min(i + BATCH_SIZE, updateArgs.size())));
        }
        return pending;
    }

    private void batchLinkPrerequisites(List<PendingCourse> pending, Map<String, String> codeToId) {
        Map<String, Set<String>> graph = validator.loadPrerequisiteGraph();
        List<Object[]> insertArgs = new ArrayList<>();
        for (PendingCourse pc : pending) {
            String prereqs = pc.item().prerequisites();
            if (!notBlank(prereqs)) continue;
            String cid = pc.id().toString();
            for (String part : prereqs.split("[,;]")) {
                String prereqCode = part.strip().toUpperCase();
                String prereqId = prereqCode.isBlank() ? null : codeToId.get(prereqCode);
                if (prereqId == null || prereqId.equals(cid)) continue;
                Set<String> edges = graph.computeIfAbsent(cid, k -> new HashSet<>());
                if (edges.contains(prereqId)) continue;
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

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }
    private static String blankToNull(String v) { return notBlank(v) ? v.strip() : null; }
    private static String truncate(String v, int max) { return v == null || v.length() <= max ? v : v.substring(0, max); }

    private record PendingCourse(UUID id, ImportConfirmItem item) {}
}
