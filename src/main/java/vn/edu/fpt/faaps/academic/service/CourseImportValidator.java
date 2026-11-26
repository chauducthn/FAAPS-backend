package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CategoryItem;

final class CourseImportValidator {
    private static final String CODE_PART = "(?:[A-Z]{3}\\d{2}[\\dx][a-z]?\\*?|[A-Z][A-Za-z]{1,5}\\d{0,2}(?:[_-][A-Za-z0-9.*]+)*(?:\\*\\.?\\d+(?:\\.\\d+)?)?\\*?)";
    private static final String CODE_REGEX = "^" + CODE_PART + "(?:/" + CODE_PART + ")*$";
    private final JdbcTemplate jdbc;

    CourseImportValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<String> loadExistingCourseCodes() {
        return new HashSet<>(jdbc.query("SELECT UPPER(code) FROM courses", (rs, n) -> rs.getString(1)));
    }

    Map<String, Set<String>> loadPrerequisiteGraph() {
        Map<String, Set<String>> graph = new HashMap<>();
        jdbc.query("SELECT course_id, prerequisite_course_id FROM course_prerequisites", rs -> {
            String cid = rs.getString(1);
            String pid = rs.getString(2);
            if (cid != null && pid != null) {
                graph.computeIfAbsent(cid, k -> new HashSet<>()).add(pid);
            }
        });
        return graph;
    }

    List<String> validateRow(String code, String name, String creditsStr, String catStr,
            String statusStr, String materialLink,
            Set<String> seenCodesInFile, Set<String> existingCodesInDb,
            List<CategoryItem> cats, Map<String, UUID> catMap) {
        List<String> errors = new ArrayList<>();
        String cleanCode = code == null ? "" : code.replaceAll("\\s+", "");
        if (cleanCode.isBlank()) {
            errors.add("Course code is required");
        } else if (!cleanCode.matches(CODE_REGEX)) {
            errors.add("Course code has invalid format (e.g. CSD201, SSL101c, IoT_GRA_ELE, NWC203c/NWC204)");
        } else {
            String upper = cleanCode.toUpperCase();
            if (!seenCodesInFile.add(upper)) {
                errors.add("Duplicate course code in uploaded file: " + cleanCode);
            } else if (existingCodesInDb.contains(upper)) {
                errors.add("Course code already exists in database: " + cleanCode);
            }
        }

        if (name.isBlank()) {
            errors.add("Course name is required");
        }
        try {
            int credits = (int) Double.parseDouble(creditsStr.trim());
            if (credits < 0 || credits > 30) {
                errors.add("Credits must be between 0 and 30");
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
        if (statusStr != null && !statusStr.isBlank()
                && !List.of("ACTIVE", "INACTIVE").contains(statusStr.strip().toUpperCase())) {
            errors.add("Status must be ACTIVE or INACTIVE");
        }
        if (materialLink != null && !materialLink.isBlank()) {
            String link = materialLink.strip();
            if (link.length() > 500 || !link.matches("(?i)^https?://\\S+\\.\\S+$")) {
                errors.add("Material link must be a valid http(s) URL");
            }
        }
        return errors;
    }

    String normalizeStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return "ACTIVE";
        }
        String upper = statusStr.strip().toUpperCase();
        return List.of("ACTIVE", "INACTIVE").contains(upper) ? upper : "ACTIVE";
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

    boolean createsCycleInGraph(Map<String, Set<String>> graph, String courseId, String candidatePrerequisiteId) {
        if (courseId.equalsIgnoreCase(candidatePrerequisiteId)) {
            return true;
        }
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(candidatePrerequisiteId);
        visited.add(candidatePrerequisiteId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String dep : graph.getOrDefault(current, Set.of())) {
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
