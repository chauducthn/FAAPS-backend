package vn.edu.fpt.faaps.academic.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages FPTU study blocks (Prep Part, Block 10W/5W/3W) and global per-semester credit/course limits. */
@Service
public class AcademicBlockConfigService {
    private static final Set<String> TYPES = Set.of("PREP", "MAIN", "FAST", "INTENSIVE");
    private final JdbcTemplate jdbc;

    public AcademicBlockConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTablesAndSeed();
    }

    private void ensureTablesAndSeed() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS academic_blocks (
                        id CHAR(36) PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
                        block_type VARCHAR(24) NOT NULL DEFAULT 'MAIN', duration_weeks INT NOT NULL DEFAULT 10,
                        max_courses INT NOT NULL DEFAULT 5, max_credits INT NOT NULL DEFAULT 15,
                        sort_order INT NOT NULL DEFAULT 0, is_active BOOLEAN NOT NULL DEFAULT TRUE,
                        description VARCHAR(255) NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS academic_semester_limits (
                        id INT PRIMARY KEY, max_credits_per_semester INT NOT NULL DEFAULT 21,
                        max_courses_per_semester INT NOT NULL DEFAULT 7, prep_parts_per_semester INT NOT NULL DEFAULT 2,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
            Integer limitCount = jdbc.queryForObject("SELECT COUNT(*) FROM academic_semester_limits", Integer.class);
            if (limitCount == null || limitCount == 0) {
                jdbc.update("""
                        INSERT INTO academic_semester_limits
                            (id, max_credits_per_semester, max_courses_per_semester, prep_parts_per_semester)
                        VALUES (1, 21, 7, 2)
                        """);
            }
            Integer blockCount = jdbc.queryForObject("SELECT COUNT(*) FROM academic_blocks", Integer.class);
            if (blockCount == null || blockCount == 0) {
                seedBlock("PREP_PART", "English Prep Part (Part 1 / 2)", "PREP", 7, 1, 0, 1,
                        "Preparatory English (PEN/TRS/LUK) — 2 parts per semester, 1 level per 7-week part.");
                seedBlock("BLOCK_10W", "Block 10W (Main Term)", "MAIN", 10, 6, 18, 2,
                        "Standard 10-week semester block for core, specialization, and mandatory courses.");
                seedBlock("BLOCK_5W", "Block 5W (Half-Term)", "FAST", 5, 3, 9, 3,
                        "Accelerated 5-week half-term block (double slot density).");
                seedBlock("BLOCK_3W", "Block 3W (Intensive / Retake)", "INTENSIVE", 3, 2, 6, 4,
                        "End-of-term 3-week intensive block for retakes, grade improvement, or fast-track.");
            }
        } catch (RuntimeException ignored) { /* Minimal test schemas may not allow DDL. */ }
    }

    private void seedBlock(String code, String name, String type, int weeks, int maxCourses,
            int maxCredits, int order, String desc) {
        jdbc.update("""
                INSERT INTO academic_blocks
                    (id, code, name, block_type, duration_weeks, max_courses, max_credits, sort_order, is_active, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """, UUID.randomUUID().toString(), code, name, type, weeks, maxCourses, maxCredits, order, desc);
    }

    public ConfigView getConfig() {
        return new ConfigView(loadLimits(), listBlocks());
    }

    public SemesterLimits loadLimits() {
        try {
            return jdbc.query("""
                    SELECT max_credits_per_semester, max_courses_per_semester, prep_parts_per_semester
                    FROM academic_semester_limits WHERE id = 1
                    """, (rs, n) -> new SemesterLimits(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                    .stream().findFirst().orElse(new SemesterLimits(21, 7, 2));
        } catch (RuntimeException ignored) {
            return new SemesterLimits(21, 7, 2);
        }
    }

    public List<BlockItem> listBlocks() {
        try {
            return jdbc.query("""
                    SELECT id, code, name, block_type, duration_weeks, max_courses, max_credits,
                           sort_order, is_active, description
                    FROM academic_blocks ORDER BY sort_order ASC, duration_weeks DESC, code ASC
                    """, (rs, n) -> new BlockItem(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                    rs.getBoolean(9), rs.getString(10)));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    @Transactional
    public SemesterLimits updateLimits(SemesterLimits input) {
        int maxCr = clamp(input.maxCreditsPerSemester(), 6, 36);
        int maxCo = clamp(input.maxCoursesPerSemester(), 1, 15);
        int prepParts = clamp(input.prepPartsPerSemester(), 1, 4);
        int updated = jdbc.update("""
                UPDATE academic_semester_limits
                SET max_credits_per_semester = ?, max_courses_per_semester = ?,
                    prep_parts_per_semester = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """, maxCr, maxCo, prepParts);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO academic_semester_limits
                        (id, max_credits_per_semester, max_courses_per_semester, prep_parts_per_semester)
                    VALUES (1, ?, ?, ?)
                    """, maxCr, maxCo, prepParts);
        }
        return loadLimits();
    }

    @Transactional
    public BlockItem createBlock(BlockInput input) {
        String code = normalizeCode(input.code());
        String name = normalizeRequired(input.name(), "Block name is required");
        String type = normalizeType(input.blockType());
        int weeks = clamp(input.durationWeeks(), 1, 30);
        int maxCourses = clamp(input.maxCourses(), 1, 15);
        int maxCredits = clamp(input.maxCredits(), 0, 36);
        int sortOrder = input.sortOrder() != null ? input.sortOrder() : 10;
        boolean active = input.active() == null || input.active();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO academic_blocks
                    (id, code, name, block_type, duration_weeks, max_courses, max_credits, sort_order, is_active, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, code, name, type, weeks, maxCourses, maxCredits, sortOrder, active, cleanOpt(input.description()));
        return listBlocks().stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Block created but not found"));
    }

    @Transactional
    public BlockItem updateBlock(String id, BlockInput input) {
        String code = normalizeCode(input.code());
        String name = normalizeRequired(input.name(), "Block name is required");
        String type = normalizeType(input.blockType());
        int weeks = clamp(input.durationWeeks(), 1, 30);
        int maxCourses = clamp(input.maxCourses(), 1, 15);
        int maxCredits = clamp(input.maxCredits(), 0, 36);
        int sortOrder = input.sortOrder() != null ? input.sortOrder() : 10;
        boolean active = input.active() == null || input.active();
        int updated = jdbc.update("""
                UPDATE academic_blocks
                SET code = ?, name = ?, block_type = ?, duration_weeks = ?, max_courses = ?,
                    max_credits = ?, sort_order = ?, is_active = ?, description = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, code, name, type, weeks, maxCourses, maxCredits, sortOrder, active, cleanOpt(input.description()), id);
        if (updated == 0) throw new IllegalArgumentException("Academic block not found");
        return listBlocks().stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Academic block not found"));
    }

    @Transactional
    public void deleteBlock(String id) {
        int deleted = jdbc.update("DELETE FROM academic_blocks WHERE id = ?", id);
        if (deleted == 0) throw new IllegalArgumentException("Academic block not found");
    }

    private static String normalizeCode(String v) {
        String s = normalizeRequired(v, "Block code is required").toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
    private static String normalizeRequired(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(msg);
        return v.strip();
    }
    private static String normalizeType(String v) {
        String t = v == null ? "MAIN" : v.strip().toUpperCase(Locale.ROOT);
        return TYPES.contains(t) ? t : "MAIN";
    }
    private static String cleanOpt(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.strip();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
    private static int clamp(Integer v, int min, int max) {
        if (v == null) return min;
        return Math.max(min, Math.min(max, v));
    }

    public record SemesterLimits(int maxCreditsPerSemester, int maxCoursesPerSemester, int prepPartsPerSemester) {}
    public record BlockItem(String id, String code, String name, String blockType, int durationWeeks,
            int maxCourses, int maxCredits, int sortOrder, boolean active, String description) {}
    public record BlockInput(String code, String name, String blockType, Integer durationWeeks,
            Integer maxCourses, Integer maxCredits, Integer sortOrder, Boolean active, String description) {}
    public record ConfigView(SemesterLimits limits, List<BlockItem> blocks) {}
}
