package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists per-student custom semester & block placements for curriculum courses. */
@Service
public class StudentBlockPlanService {
    private static final Set<String> MODES = Set.of("CUSTOM", "RETAKE", "DEFERRED", "EARLY");
    private final JdbcTemplate jdbc;

    public StudentBlockPlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTable();
    }

    private void ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS student_block_placements ("
                    + "student_id CHAR(36) NOT NULL, course_code VARCHAR(64) NOT NULL, "
                    + "semester_number INT NOT NULL, block_code VARCHAR(32) NOT NULL, "
                    + "plan_mode VARCHAR(16) NOT NULL DEFAULT 'CUSTOM', note VARCHAR(255) NULL, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (student_id, course_code))");
        } catch (RuntimeException ignored) { /* minimal test schema */ }
    }

    public List<BlockPlacement> list(UUID studentId) {
        try {
            return jdbc.query("""
                    SELECT course_code, semester_number, block_code, plan_mode, note
                    FROM student_block_placements WHERE student_id = ? ORDER BY semester_number ASC, course_code ASC
                    """, (rs, n) -> new BlockPlacement(rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)), studentId.toString());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    @Transactional
    public List<BlockPlacement> upsert(UUID studentId, PlacementInput input) {
        if (input == null || input.courseCode() == null || input.courseCode().isBlank()) {
            throw new IllegalArgumentException("courseCode is required");
        }
        if (input.blockCode() == null || input.blockCode().isBlank()) {
            throw new IllegalArgumentException("blockCode is required");
        }
        String code = input.courseCode().strip().toUpperCase(Locale.ROOT);
        int sem = Math.max(-6, Math.min(12, input.semesterNumber() == null ? 0 : input.semesterNumber()));
        String block = input.blockCode().strip().toUpperCase(Locale.ROOT);
        String mode = normalizeMode(input.planMode());
        String note = input.note() == null || input.note().isBlank() ? null
                : input.note().strip().substring(0, Math.min(255, input.note().strip().length()));

        int updated = jdbc.update("""
                UPDATE student_block_placements
                SET semester_number = ?, block_code = ?, plan_mode = ?, note = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND course_code = ?
                """, sem, block, mode, note, studentId.toString(), code);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO student_block_placements
                        (student_id, course_code, semester_number, block_code, plan_mode, note)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, studentId.toString(), code, sem, block, mode, note);
        }
        return list(studentId);
    }

    @Transactional
    public List<BlockPlacement> remove(UUID studentId, String courseCode) {
        if (courseCode != null && !courseCode.isBlank()) {
            jdbc.update("DELETE FROM student_block_placements WHERE student_id = ? AND course_code = ?",
                    studentId.toString(), courseCode.strip().toUpperCase(Locale.ROOT));
        }
        return list(studentId);
    }

    @Transactional
    public List<BlockPlacement> resetAll(UUID studentId) {
        jdbc.update("DELETE FROM student_block_placements WHERE student_id = ?", studentId.toString());
        return List.of();
    }

    public Map<String, List<String>> prerequisiteMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    SELECT UPPER(c.code), UPPER(p.code)
                    FROM course_prerequisites cp
                    JOIN courses c ON c.id = cp.course_id
                    JOIN courses p ON p.id = cp.prerequisite_course_id
                    """, rs -> {
                map.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            });
        } catch (RuntimeException ignored) { /* minimal test schema */ }
        return map;
    }

    private static String normalizeMode(String v) {
        if (v == null || v.isBlank()) return "CUSTOM";
        String upper = v.strip().toUpperCase(Locale.ROOT);
        return MODES.contains(upper) ? upper : "CUSTOM";
    }

    public record BlockPlacement(String courseCode, int semesterNumber, String blockCode,
            String planMode, String note) {}
    public record PlacementInput(String courseCode, Integer semesterNumber, String blockCode,
            String planMode, String note) {}
}
