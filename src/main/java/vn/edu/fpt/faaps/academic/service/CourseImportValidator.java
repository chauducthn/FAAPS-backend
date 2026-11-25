package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;

final class CourseImportValidator {
    private final JdbcTemplate jdbc;

    CourseImportValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> validateRow(String code, String name, String creditsStr, String catStr,
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

    int parseCredits(String creditsStr) {
        try {
            return (int) Double.parseDouble(creditsStr.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    UUID resolveCategoryId(String catStr, List<CategoryItem> cats, Map<String, UUID> catMap) {
        if (catStr != null && !catStr.isBlank()) {
            UUID id = catMap.get(catStr.trim().toUpperCase());
            if (id != null) {
                return id;
            }
        }
        return cats.isEmpty() ? null : cats.get(0).id();
    }

    boolean createsCycle(String courseId, String candidatePrerequisiteId) {
        if (courseId.equalsIgnoreCase(candidatePrerequisiteId)) {
            return true;
        }
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(candidatePrerequisiteId);
        visited.add(candidatePrerequisiteId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> next = jdbc.query(
                    "SELECT prerequisite_course_id FROM course_prerequisites WHERE course_id = ?",
                    (rs, row) -> rs.getString(1), current);
            for (String dep : next) {
                if (dep == null) {
                    continue;
                }
                if (dep.equalsIgnoreCase(courseId)) {
                    return true;
                }
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return false;
    }
}
