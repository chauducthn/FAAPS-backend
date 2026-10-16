package vn.edu.fpt.faaps.admin;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCourseService {
    private final JdbcTemplate jdbc;

    public AdminCourseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String search, String status, UUID categoryId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL") && !List.of("ACTIVE", "INACTIVE").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND c.status = ?");
            params.add(status.toUpperCase());
        }

        if (categoryId != null) {
            where.append(" AND c.category_id = ?");
            params.add(categoryId.toString());
        }

        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
        }

        String countSql = "SELECT COUNT(*) FROM courses c" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;

        String orderBy = " ORDER BY c.code ASC, c.id ASC";
        if (sort != null && !sort.isBlank()) {
            orderBy = switch (sort) {
                case "code,desc" -> " ORDER BY c.code DESC, c.id DESC";
                case "name,asc" -> " ORDER BY c.name ASC, c.id ASC";
                case "name,desc" -> " ORDER BY c.name DESC, c.id DESC";
                case "credits,asc" -> " ORDER BY c.credits ASC, c.code ASC";
                case "credits,desc" -> " ORDER BY c.credits DESC, c.code ASC";
                case "createdAt,desc" -> " ORDER BY c.created_at DESC, c.id DESC";
                case "createdAt,asc" -> " ORDER BY c.created_at ASC, c.id ASC";
                default -> " ORDER BY c.code ASC, c.id ASC";
            };
        }

        String sql = """
                SELECT c.id, c.code, c.name, c.credits, c.category_id,
                       cat.code AS category_code, cat.name AS category_name,
                       c.description, c.objectives, c.learning_outcomes,
                       c.status, c.created_at, c.updated_at,
                       (SELECT COUNT(*) FROM course_prerequisites cp WHERE cp.course_id = c.id) AS prerequisite_count
                FROM courses c
                LEFT JOIN course_categories cat ON cat.id = c.category_id
                """ + where + orderBy + " LIMIT ? OFFSET ?";

        params.add(size);
        params.add(page * size);

        List<CourseSummary> items = jdbc.query(sql, (rs, rowNum) -> mapSummary(rs), params.toArray());
        long totalPages = (totalElements + size - 1) / size;
        return new Page(items, page, size, totalElements, totalPages);
    }

    public CourseDetail detail(UUID id) {
        String sql = """
                SELECT c.id, c.code, c.name, c.credits, c.category_id,
                       cat.code AS category_code, cat.name AS category_name,
                       c.description, c.objectives, c.learning_outcomes,
                       c.status, c.created_at, c.updated_at
                FROM courses c
                LEFT JOIN course_categories cat ON cat.id = c.category_id
                WHERE c.id = ?
                """;
        List<CourseDetail> results = jdbc.query(sql, (rs, rowNum) -> mapDetail(rs), id.toString());
        if (results.isEmpty()) {
            throw new NotFound();
        }
        CourseDetail base = results.get(0);

        List<PrerequisiteItem> prerequisites = jdbc.query("""
                SELECT cp.id, cp.prerequisite_course_id, prereq.code AS prerequisite_code,
                       prereq.name AS prerequisite_name, cp.group_no, cp.type, cp.minimum_score
                FROM course_prerequisites cp
                JOIN courses prereq ON prereq.id = cp.prerequisite_course_id
                WHERE cp.course_id = ?
                ORDER BY cp.group_no ASC, prereq.code ASC
                """,
                (rs, rowNum) -> new PrerequisiteItem(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("prerequisite_course_id")),
                        rs.getString("prerequisite_code"),
                        rs.getString("prerequisite_name"),
                        rs.getInt("group_no"),
                        rs.getString("type"),
                        rs.getBigDecimal("minimum_score")
                ),
                id.toString());

        return new CourseDetail(base.id(), base.code(), base.name(), base.credits(),
                base.categoryId(), base.categoryCode(), base.categoryName(),
                base.description(), base.objectives(), base.learningOutcomes(),
                base.status(), base.createdAt(), base.updatedAt(), prerequisites);
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

        try {
            Integer curriculumRefs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curriculum_courses WHERE course_id = ?", Integer.class, id.toString());
            if (curriculumRefs != null && curriculumRefs > 0) {
                throw new Conflict("Cannot delete course because it is part of " + curriculumRefs + " curriculum course list(s)");
            }
        } catch (Exception ignored) {
        }

        jdbc.update("DELETE FROM course_prerequisites WHERE course_id = ?", id.toString());
        jdbc.update("DELETE FROM courses WHERE id = ?", id.toString());
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
        List<CategoryItem> cats = categories();
        Map<String, UUID> catMap = new HashMap<>();
        for (CategoryItem cat : cats) {
            catMap.put(cat.code().toUpperCase(), cat.id());
            catMap.put(cat.name().toUpperCase(), cat.id());
        }

        List<ImportRowItem> rows = new ArrayList<>();
        Set<String> seenCodesInFile = new HashSet<>();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            if (lastRow < 0) {
                return new ImportPreviewResult(0, 0, 0, List.of());
            }

            int headerRowNum = -1;
            int codeCol = -1;
            Map<String, Integer> colMap = new HashMap<>();

            for (int r = 0; r <= Math.min(5, lastRow); r++) {
                Row rRow = sheet.getRow(r);
                if (rRow == null) continue;
                Map<String, Integer> candidateColMap = new HashMap<>();
                for (int c = 0; c < rRow.getLastCellNum(); c++) {
                    Cell cell = rRow.getCell(c);
                    if (cell != null) {
                        String key = cleanKey(getCellString(cell));
                        if (!key.isBlank()) {
                            candidateColMap.put(key, c);
                        }
                    }
                }
                int candidateCodeCol = findCol(candidateColMap, "subject code", "subjectcode", "course code", "code", "mã môn", "mã học phần");
                int candidateNameCol = findCol(candidateColMap, "course name english", "course name", "name", "syllabus name", "tên môn", "tên học phần");
                int candidateCreditsCol = findCol(candidateColMap, "nocredit", "no credit", "nocredits", "no credits", "credits", "credit", "tín chỉ", "số tín chỉ");

                if (candidateCodeCol != -1 && (candidateNameCol != -1 || candidateCreditsCol != -1)) {
                    headerRowNum = r;
                    codeCol = candidateCodeCol;
                    colMap = candidateColMap;
                    break;
                }
            }

            if (codeCol != -1) {
                int nameCol = findCol(colMap, "course name english", "course name", "name", "syllabus name", "tên môn", "tên học phần");
                int creditsCol = findCol(colMap, "nocredit", "no credit", "nocredits", "no credits", "credits", "credit", "tín chỉ", "số tín chỉ");
                int catCol = findCol(colMap, "category", "category code", "course category", "khối kiến thức", "department", "chuyên ngành");
                int descCol = findCol(colMap, "description", "mô tả", "course description");
                int prereqCol = findCol(colMap, "pre-requisite", "prerequisite", "prerequisites", "pre-requisites", "pre requisite", "tiên quyết", "môn tiên quyết");
                int objCol = findCol(colMap, "objectives", "studenttasks", "student tasks", "mục tiêu");
                int loCol = findCol(colMap, "learning outcomes", "learning_outcomes", "chuẩn đầu ra");

                for (int r = headerRowNum + 1; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String code = getCellString(row.getCell(codeCol));
                    String name = nameCol != -1 ? getCellString(row.getCell(nameCol)) : "";
                    String creditsStr = creditsCol != -1 ? getCellString(row.getCell(creditsCol)) : "";
                    String catStr = catCol != -1 ? getCellString(row.getCell(catCol)) : "";
                    String desc = descCol != -1 ? getCellString(row.getCell(descCol)) : "";
                    String prereqs = prereqCol != -1 ? getCellString(row.getCell(prereqCol)) : "";
                    String obj = objCol != -1 ? getCellString(row.getCell(objCol)) : "";
                    String lo = loCol != -1 ? getCellString(row.getCell(loCol)) : "";

                    if (code.isBlank() && name.isBlank()) {
                        continue;
                    }

                    List<String> errors = validateRow(code, name, creditsStr, catStr, seenCodesInFile, cats, catMap);
                    int credits = parseCredits(creditsStr);
                    UUID categoryId = resolveCategoryId(catStr, cats, catMap);
                    String status = errors.isEmpty() ? "VALID" : "ERROR";
                    rows.add(new ImportRowItem(r, code.toUpperCase(), name, credits,
                            categoryId != null ? categoryId.toString() : null, catStr, desc, prereqs, obj, lo, status, errors));
                }
            } else {
                List<Map<String, String>> blocks = new ArrayList<>();
                Map<String, String> currentBlock = new HashMap<>();

                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String k = cleanKey(getCellString(row.getCell(0)));
                    String v = getCellString(row.getCell(1));
                    if (k.isBlank() && v.isBlank()) {
                        if (!currentBlock.isEmpty()) {
                            blocks.add(currentBlock);
                            currentBlock = new HashMap<>();
                        }
                        continue;
                    }
                    boolean isNewCourseStart = !currentBlock.isEmpty() && (
                            (k.equals("syllabus id") && currentBlock.containsKey("syllabus id"))
                            || (k.equals("subject code") && currentBlock.containsKey("subject code"))
                            || (k.equals("code") && currentBlock.containsKey("code"))
                            || (k.equals("mã môn") && currentBlock.containsKey("mã môn"))
                    );
                    if (isNewCourseStart) {
                        blocks.add(currentBlock);
                        currentBlock = new HashMap<>();
                    }
                    if (!k.isBlank()) {
                        currentBlock.put(k, v);
                    }
                }
                if (!currentBlock.isEmpty()) {
                    blocks.add(currentBlock);
                }

                int blockIndex = 1;
                for (Map<String, String> block : blocks) {
                    String code = findVal(block, "subject code", "subjectcode", "course code", "code", "mã môn", "mã học phần");
                    String name = findVal(block, "course name english", "course name", "name", "syllabus name", "tên môn", "tên học phần");
                    String creditsStr = findVal(block, "nocredit", "no credit", "nocredits", "no credits", "credits", "credit", "tín chỉ", "số tín chỉ");
                    String catStr = findVal(block, "category", "category code", "course category", "khối kiến thức", "department", "chuyên ngành");
                    String desc = findVal(block, "description", "mô tả", "course description");
                    String prereqs = findVal(block, "pre-requisite", "prerequisite", "prerequisites", "pre-requisites", "pre requisite", "tiên quyết", "môn tiên quyết");
                    String obj = findVal(block, "objectives", "studenttasks", "student tasks", "mục tiêu");
                    String lo = findVal(block, "learning outcomes", "learning_outcomes", "chuẩn đầu ra");

                    if (code.isBlank() && name.isBlank()) {
                        continue;
                    }

                    List<String> errors = validateRow(code, name, creditsStr, catStr, seenCodesInFile, cats, catMap);
                    int credits = parseCredits(creditsStr);
                    UUID categoryId = resolveCategoryId(catStr, cats, catMap);
                    String status = errors.isEmpty() ? "VALID" : "ERROR";

                    rows.add(new ImportRowItem(blockIndex++, code.toUpperCase(), name, credits,
                            categoryId != null ? categoryId.toString() : null, catStr, desc, prereqs, obj, lo, status, errors));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read Excel file: " + e.getMessage());
        }

        int validCount = (int) rows.stream().filter(i -> i.status().equals("VALID")).count();
        int errorCount = rows.size() - validCount;
        return new ImportPreviewResult(rows.size(), validCount, errorCount, rows);
    }

    @Transactional
    public int confirmImport(List<ImportConfirmItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }

        List<CategoryItem> cats = categories();
        UUID defaultCatId = cats.isEmpty() ? null : cats.get(0).id();

        record PendingCourse(UUID id, ImportConfirmItem item) {}
        List<PendingCourse> pending = new ArrayList<>();

        for (ImportConfirmItem item : items) {
            if (item.code() == null || item.code().isBlank() || item.name() == null || item.name().isBlank()) {
                continue;
            }
            UUID catId = item.categoryId();
            if (catId == null) {
                catId = defaultCatId;
            }
            if (catId == null) continue;

            String code = item.code().strip().toUpperCase();
            List<String> existingIds = jdbc.query("SELECT id FROM courses WHERE code = ?",
                    (rs, rowNum) -> rs.getString("id"), code);
            UUID courseId;
            if (existingIds.isEmpty()) {
                courseId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO courses (id, code, name, credits, category_id, description, objectives, learning_outcomes, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                        courseId.toString(), code, item.name().strip(),
                        item.credits() > 0 ? item.credits() : 3, catId.toString(),
                        item.description(), item.objectives(), item.learningOutcomes());
            } else {
                courseId = UUID.fromString(existingIds.get(0));
            }
            pending.add(new PendingCourse(courseId, item));
        }

        for (PendingCourse pc : pending) {
            String prereqs = pc.item().prerequisites();
            if (prereqs != null && !prereqs.isBlank()) {
                String[] parts = prereqs.split("[,;]");
                for (String part : parts) {
                    String prereqCode = part.strip().toUpperCase();
                    if (!prereqCode.isBlank()) {
                        List<String> prereqIds = jdbc.query("SELECT id FROM courses WHERE code = ?",
                                (rs, rowNum) -> rs.getString("id"), prereqCode);
                        if (!prereqIds.isEmpty()) {
                            UUID prereqCourseId = UUID.fromString(prereqIds.get(0));
                            if (!prereqCourseId.equals(pc.id())) {
                                Integer count = jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM course_prerequisites WHERE course_id = ? AND prerequisite_course_id = ?",
                                        Integer.class, pc.id().toString(), prereqCourseId.toString());
                                if (count == null || count == 0) {
                                    try {
                                        jdbc.update("""
                                                INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                                                VALUES (?, ?, ?, 1, 'PREREQUISITE', 5.00, CURRENT_TIMESTAMP)
                                                """,
                                                UUID.randomUUID().toString(), pc.id().toString(), prereqCourseId.toString());
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return pending.size();
    }

    private List<String> validateRow(String code, String name, String creditsStr, String catStr,
            Set<String> seenCodesInFile, List<CategoryItem> cats, Map<String, UUID> catMap) {
        List<String> errors = new ArrayList<>();
        if (code.isBlank()) {
            errors.add("Course code is required");
        } else if (!code.matches("^[A-Za-z0-9_-]{2,20}$")) {
            errors.add("Course code format is invalid (2-20 alphanumeric characters)");
        } else if (!seenCodesInFile.add(code.toUpperCase())) {
            errors.add("Duplicate course code in uploaded file: " + code);
        } else {
            Integer countInDb = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM courses WHERE code = ?", Integer.class, code.toUpperCase());
            if (countInDb != null && countInDb > 0) {
                errors.add("Course code already exists in database: " + code);
            }
        }

        if (name.isBlank()) {
            errors.add("Course name is required");
        }

        try {
            int credits = (int) Double.parseDouble(creditsStr.trim());
            if (credits <= 0 || credits > 30) {
                errors.add("Credits must be between 1 and 30");
            }
        } catch (Exception e) {
            errors.add("Credits must be a valid number");
        }

        if (!catStr.isBlank()) {
            if (!catMap.containsKey(catStr.toUpperCase())) {
                errors.add("Category '" + catStr + "' does not exist");
            }
        } else if (cats.isEmpty()) {
            errors.add("Course category is required");
        }
        return errors;
    }

    private int parseCredits(String creditsStr) {
        try {
            return (int) Double.parseDouble(creditsStr.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private UUID resolveCategoryId(String catStr, List<CategoryItem> cats, Map<String, UUID> catMap) {
        if (catStr != null && !catStr.isBlank()) {
            UUID id = catMap.get(catStr.trim().toUpperCase());
            if (id != null) return id;
        }
        return cats.isEmpty() ? null : cats.get(0).id();
    }

    private String cleanKey(String str) {
        if (str == null) return "";
        return str.trim().toLowerCase().replaceAll("[:：]+$", "").trim();
    }

    private String findVal(Map<String, String> map, String... names) {
        for (String name : names) {
            String cleaned = cleanKey(name);
            if (map.containsKey(cleaned)) {
                return map.get(cleaned).trim();
            }
        }
        return "";
    }

    private void savePrerequisites(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites == null || prerequisites.isEmpty()) {
            return;
        }
        for (PrerequisiteInput p : prerequisites) {
            if (p.prerequisiteCourseId() == null || p.prerequisiteCourseId().equals(courseId)) {
                continue;
            }
            UUID pid = UUID.randomUUID();
            String type = (p.type() != null && !p.type().isBlank()) ? p.type().toUpperCase() : "PREREQUISITE";
            int group = p.groupNo() != null && p.groupNo() > 0 ? p.groupNo() : 1;
            BigDecimal minScore = p.minimumScore() != null ? p.minimumScore() : new BigDecimal("5.00");
            try {
                jdbc.update("""
                        INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                        pid.toString(), courseId.toString(), p.prerequisiteCourseId().toString(), group, type, minScore);
            } catch (Exception ignored) {
            }
        }
    }

    private int findCol(Map<String, Integer> map, String... names) {
        for (String name : names) {
            if (map.containsKey(name.toLowerCase())) {
                return map.get(name.toLowerCase());
            }
        }
        return -1;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((long) cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return "";
    }

    private CourseSummary mapSummary(ResultSet rs) throws SQLException {
        return new CourseSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("credits"),
                rs.getString("category_id") != null ? UUID.fromString(rs.getString("category_id")) : null,
                rs.getString("category_code"),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getInt("prerequisite_count"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
        );
    }

    private CourseDetail mapDetail(ResultSet rs) throws SQLException {
        return new CourseDetail(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("credits"),
                rs.getString("category_id") != null ? UUID.fromString(rs.getString("category_id")) : null,
                rs.getString("category_code"),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getString("objectives"),
                rs.getString("learning_outcomes"),
                rs.getString("status"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null,
                List.of()
        );
    }

    public record CourseSummary(UUID id, String code, String name, int credits, UUID categoryId,
            String categoryCode, String categoryName, String description, String status, int prerequisiteCount,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record CourseDetail(UUID id, String code, String name, int credits, UUID categoryId,
            String categoryCode, String categoryName, String description, String objectives,
            String learningOutcomes, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            List<PrerequisiteItem> prerequisites) {
    }

    public record PrerequisiteItem(UUID id, UUID prerequisiteCourseId, String prerequisiteCode,
            String prerequisiteName, int groupNo, String type, BigDecimal minimumScore) {
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
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
