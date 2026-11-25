package vn.edu.fpt.faaps.academic.service;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.Conflict;

final class AdminCourseRowWriter {
    private static final Pattern MATERIAL_LINK_PATTERN = Pattern.compile("^https?://\\S+$");

    private final JdbcTemplate jdbc;

    AdminCourseRowWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String normalizeAndValidateMaterialLink(String raw) {
        if (raw == null || raw.isBlank() || AdminCourseService.KEEP_EXISTING_MATERIAL_LINK.equals(raw)) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("Material link must be at most 500 characters");
        }
        if (!MATERIAL_LINK_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Enter a full link starting with http:// or https://");
        }
        return trimmed;
    }

    static String extractMaterialLink(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        return (trimmed.startsWith("http://") || trimmed.startsWith("https://")) ? trimmed : null;
    }

    void insertCourseRow(UUID id, String code, String name, int credits, String categoryId,
            String description, String objectives, String learningOutcomes, String materialLink, String status) {
        try {
            try {
                jdbc.update("""
                        INSERT INTO courses (id, code, name, credits, category_id, description, objectives,
                            learning_outcomes, material_link, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, id.toString(), code, name, credits, categoryId,
                        description, objectives, learningOutcomes, materialLink, status);
            } catch (DuplicateKeyException dup) {
                throw dup;
            } catch (Exception fallbackEx) {
                jdbc.update("""
                        INSERT INTO courses (id, code, name, credits, category_id, description, objectives,
                            learning_outcomes, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, id.toString(), code, name, credits, categoryId,
                        description, objectives, learningOutcomes, status);
            }
        } catch (DuplicateKeyException e) {
            throw new Conflict("Course code already exists");
        }
    }

    void updateCourseRow(UUID id, String code, String name, int credits, String categoryId,
            String description, String objectives, String learningOutcomes, String materialLink, String status) {
        try {
            try {
                jdbc.update("""
                        UPDATE courses SET code = ?, name = ?, credits = ?, category_id = ?, description = ?,
                            objectives = ?, learning_outcomes = ?, material_link = ?, status = ?,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, code, name, credits, categoryId, description, objectives,
                        learningOutcomes, materialLink, status, id.toString());
            } catch (DuplicateKeyException dup) {
                throw dup;
            } catch (Exception fallbackEx) {
                jdbc.update("""
                        UPDATE courses SET code = ?, name = ?, credits = ?, category_id = ?, description = ?,
                            objectives = ?, learning_outcomes = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, code, name, credits, categoryId, description, objectives,
                        learningOutcomes, status, id.toString());
            }
        } catch (DuplicateKeyException e) {
            throw new Conflict("Course code already exists");
        }
    }
}
