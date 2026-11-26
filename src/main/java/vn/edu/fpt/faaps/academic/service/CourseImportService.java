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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportConfirmItem;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportPreviewResult;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.ImportRowItem;
import vn.edu.fpt.faaps.academic.service.CourseImportSheetParser.RawCourseRow;

final class CourseImportService {
    private static final Logger log = LoggerFactory.getLogger(CourseImportService.class);

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

        List<ImportRowItem> rows = new ArrayList<>();
        Set<String> seenCodesInFile = new HashSet<>();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            List<RawCourseRow> parsedRows = CourseImportSheetParser.parseSheet(workbook.getSheetAt(0));
            for (RawCourseRow raw : parsedRows) {
                List<String> errors = validator.validateRow(
                        raw.code(), raw.name(), raw.creditsStr(), raw.catStr(),
                        raw.statusStr(), raw.materialLink(), seenCodesInFile, cats, catMap);
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
        List<PendingCourse> pending = upsertCourses(items, defaultCatId);
        linkPrerequisites(pending);
        return pending.size();
    }

    private List<PendingCourse> upsertCourses(List<ImportConfirmItem> items, UUID defaultCatId) {
        List<PendingCourse> pending = new ArrayList<>();
        for (ImportConfirmItem item : items) {
            if (item.code() == null || item.code().isBlank() || item.name() == null || item.name().isBlank()) {
                continue;
            }
            UUID catId = item.categoryId() != null ? item.categoryId() : defaultCatId;
            if (catId == null) {
                continue;
            }
            String code = item.code().strip();
            List<String> existingIds = jdbc.query(
                    "SELECT id FROM courses WHERE UPPER(code) = ?", (rs, rowNum) -> rs.getString("id"), code.toUpperCase());
            UUID courseId;
            if (existingIds.isEmpty()) {
                courseId = UUID.randomUUID();
                String courseStatus = validator.normalizeStatus(item.status());
                String link = AdminCourseWriteHelper.normalizeAndValidateMaterialLink(item.materialLink());
                jdbc.update("""
                        INSERT INTO courses (id, code, name, credits, category_id, description, objectives,
                            learning_outcomes, material_link, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, courseId.toString(), code, item.name().strip(),
                        item.credits() >= 0 ? item.credits() : 3, catId.toString(),
                        item.description(), item.objectives(), item.learningOutcomes(), link, courseStatus);
            } else {
                courseId = UUID.fromString(existingIds.get(0));
            }
            pending.add(new PendingCourse(courseId, item));
        }
        return pending;
    }

    private void linkPrerequisites(List<PendingCourse> pending) {
        for (PendingCourse pc : pending) {
            String prereqs = pc.item().prerequisites();
            if (prereqs == null || prereqs.isBlank()) {
                continue;
            }
            for (String part : prereqs.split("[,;]")) {
                String prereqCode = part.strip().toUpperCase();
                if (prereqCode.isBlank()) {
                    continue;
                }
                List<String> prereqIds = jdbc.query(
                        "SELECT id FROM courses WHERE UPPER(code) = ?", (rs, rowNum) -> rs.getString("id"), prereqCode);
                if (prereqIds.isEmpty() || prereqIds.get(0).equals(pc.id().toString())) {
                    continue;
                }
                String prereqId = prereqIds.get(0);
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM course_prerequisites WHERE course_id = ? AND prerequisite_course_id = ?",
                        Integer.class, pc.id().toString(), prereqId);
                if (count != null && count > 0) {
                    continue;
                }
                if (validator.createsCycle(pc.id().toString(), prereqId)) {
                    throw new AdminCourseService.Conflict("Circular course prerequisite detected");
                }
                try {
                    jdbc.update("""
                            INSERT INTO course_prerequisites
                                (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                            VALUES (?, ?, ?, 1, 'PREREQUISITE', 5.00, CURRENT_TIMESTAMP)
                            """, UUID.randomUUID().toString(), pc.id().toString(), prereqId);
                } catch (DuplicateKeyException ex) {
                    log.debug("Prerequisite {} -> {} already inserted concurrently", pc.id(), prereqId);
                }
            }
        }
    }

    private record PendingCourse(UUID id, ImportConfirmItem item) {
    }
}
