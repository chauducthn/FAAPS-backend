package vn.edu.fpt.faaps.advising.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping({"/api/advisor/academic", "/advisor/academic"})
public class AdvisorAcademicLookupController {
    private final JdbcTemplate jdbc;

    public AdvisorAcademicLookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/courses")
    public List<LookupCourse> getCourses(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) {
        StringBuilder where = new StringBuilder(" WHERE c.status = 'ACTIVE'");
        List<Object> params = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)");
            params.add(term);
            params.add(term);
        }

        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.strip())) {
            String cat = category.strip().toLowerCase(Locale.ROOT);
            where.append(" AND (LOWER(cat.code) = ? OR LOWER(cat.name) = ? OR LOWER(c.category_id) = ?)");
            params.add(cat);
            params.add(cat);
            params.add(cat);
        }

        List<CourseBase> bases;
        try {
            bases = jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits,
                           COALESCE(cat.name, cat.code, '') AS category_label,
                           c.description, c.objectives, c.learning_outcomes, c.material_link
                    FROM courses c
                    LEFT JOIN course_categories cat ON cat.id = c.category_id
                    """ + where + " ORDER BY c.code ASC",
                    (rs, row) -> new CourseBase(
                            rs.getString("id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getInt("credits"),
                            rs.getString("category_label") != null ? rs.getString("category_label") : "",
                            rs.getString("description") != null ? rs.getString("description") : "",
                            rs.getString("objectives") != null ? rs.getString("objectives") : "",
                            rs.getString("learning_outcomes") != null ? rs.getString("learning_outcomes") : "",
                            rs.getString("material_link")
                    ), params.toArray());
        } catch (Exception ex) {
            bases = jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits,
                           COALESCE(cat.name, cat.code, '') AS category_label,
                           c.description, c.objectives, c.learning_outcomes
                    FROM courses c
                    LEFT JOIN course_categories cat ON cat.id = c.category_id
                    """ + where + " ORDER BY c.code ASC",
                    (rs, row) -> new CourseBase(
                            rs.getString("id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getInt("credits"),
                            rs.getString("category_label") != null ? rs.getString("category_label") : "",
                            rs.getString("description") != null ? rs.getString("description") : "",
                            rs.getString("objectives") != null ? rs.getString("objectives") : "",
                            rs.getString("learning_outcomes") != null ? rs.getString("learning_outcomes") : "",
                            null
                    ), params.toArray());
        }

        List<LookupCourse> result = new ArrayList<>(bases.size());
        for (CourseBase base : bases) {
            result.add(new LookupCourse(
                    base.id(),
                    base.code(),
                    base.name(),
                    base.credits(),
                    base.category(),
                    base.description(),
                    base.objectives(),
                    base.learningOutcomes(),
                    base.materialLink(),
                    loadPrerequisites(base.id()),
                    List.of()
            ));
        }
        return result;
    }

    @GetMapping("/courses/{id}")
    public LookupCourse getCourseDetail(@PathVariable UUID id) {
        List<CourseBase> bases;
        try {
            bases = jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits,
                           COALESCE(cat.name, cat.code, '') AS category_label,
                           c.description, c.objectives, c.learning_outcomes, c.material_link
                    FROM courses c
                    LEFT JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.id = ?
                    """, (rs, row) -> new CourseBase(
                    rs.getString("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getInt("credits"),
                    rs.getString("category_label") != null ? rs.getString("category_label") : "",
                    rs.getString("description") != null ? rs.getString("description") : "",
                    rs.getString("objectives") != null ? rs.getString("objectives") : "",
                    rs.getString("learning_outcomes") != null ? rs.getString("learning_outcomes") : "",
                    rs.getString("material_link")
            ), id.toString());
        } catch (Exception ex) {
            bases = jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits,
                           COALESCE(cat.name, cat.code, '') AS category_label,
                           c.description, c.objectives, c.learning_outcomes
                    FROM courses c
                    LEFT JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.id = ?
                    """, (rs, row) -> new CourseBase(
                    rs.getString("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getInt("credits"),
                    rs.getString("category_label") != null ? rs.getString("category_label") : "",
                    rs.getString("description") != null ? rs.getString("description") : "",
                    rs.getString("objectives") != null ? rs.getString("objectives") : "",
                    rs.getString("learning_outcomes") != null ? rs.getString("learning_outcomes") : "",
                    null
            ), id.toString());
        }

        if (bases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        CourseBase base = bases.get(0);
        return new LookupCourse(
                base.id(),
                base.code(),
                base.name(),
                base.credits(),
                base.category(),
                base.description(),
                base.objectives(),
                base.learningOutcomes(),
                base.materialLink(),
                loadPrerequisites(base.id()),
                List.of()
        );
    }

    @GetMapping("/curriculums")
    public List<LookupCurriculum> getCurriculums() {
        List<CurriculumBase> versions;
        try {
            versions = jdbc.query("""
                    SELECT cv.id, m.code AS major_code, m.name AS major_name,
                           cv.version, cv.name, cv.description,
                           COALESCE(m.total_credits, 0) AS major_credits
                    FROM curriculum_versions cv
                    JOIN majors m ON m.id = cv.major_id
                    WHERE cv.status = 'ACTIVE' AND m.status = 'ACTIVE'
                    ORDER BY m.code ASC, cv.version DESC
                    """, (rs, row) -> new CurriculumBase(
                    rs.getString("id"),
                    rs.getString("major_code"),
                    rs.getString("major_name"),
                    rs.getString("version"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getInt("major_credits")
            ));
        } catch (Exception ignored) {
            versions = jdbc.query("""
                    SELECT cv.id, m.code AS major_code, m.name AS major_name,
                           cv.version, cv.name, m.description,
                           COALESCE(m.total_credits, 0) AS major_credits
                    FROM curriculum_versions cv
                    JOIN majors m ON m.id = cv.major_id
                    WHERE cv.status = 'ACTIVE' AND m.status = 'ACTIVE'
                    ORDER BY m.code ASC, cv.version DESC
                    """, (rs, row) -> new CurriculumBase(
                    rs.getString("id"),
                    rs.getString("major_code"),
                    rs.getString("major_name"),
                    rs.getString("version"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getInt("major_credits")
            ));
        }

        List<LookupCurriculum> list = new ArrayList<>(versions.size());
        for (CurriculumBase cv : versions) {
            List<CurriculumCourseRow> rows = jdbc.query("""
                    SELECT cc.semester_number, c.code, COALESCE(c.credits, 0) AS credits
                    FROM curriculum_courses cc
                    JOIN courses c ON c.id = cc.course_id
                    WHERE cc.curriculum_version_id = ?
                    ORDER BY cc.semester_number ASC, c.code ASC
                    """, (rs, row) -> new CurriculumCourseRow(
                    rs.getInt("semester_number"),
                    rs.getString("code"),
                    rs.getInt("credits")
            ), cv.id());

            Map<Integer, List<String>> semMap = new LinkedHashMap<>();
            int computedCredits = 0;
            for (CurriculumCourseRow r : rows) {
                semMap.computeIfAbsent(r.semesterNumber(), k -> new ArrayList<>()).add(r.courseCode());
                computedCredits += r.credits();
            }

            List<LookupSemester> semesters = semMap.entrySet().stream()
                    .map(e -> new LookupSemester(e.getKey(), e.getValue()))
                    .toList();
            int totalCredits = cv.majorCredits() > 0 ? cv.majorCredits() : computedCredits;

            list.add(new LookupCurriculum(
                    cv.id(),
                    cv.majorCode(),
                    cv.majorName(),
                    cv.version(),
                    cv.name(),
                    cv.description(),
                    totalCredits,
                    semesters
            ));
        }
        return list;
    }

    private List<LookupPrerequisite> loadPrerequisites(String courseId) {
        return jdbc.query("""
                SELECT prereq.code AS course_code, prereq.name AS course_name,
                       cp.group_no, cp.minimum_score
                FROM course_prerequisites cp
                JOIN courses prereq ON prereq.id = cp.prerequisite_course_id
                WHERE cp.course_id = ?
                ORDER BY cp.group_no ASC, prereq.code ASC
                """, (rs, row) -> new LookupPrerequisite(
                rs.getString("course_code"),
                rs.getString("course_name"),
                rs.getInt("group_no"),
                rs.getBigDecimal("minimum_score")
        ), courseId);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        String code = ex.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(ex.getStatusCode()).body(ApiError.of(code, ex.getReason()));
    }

    private record CourseBase(String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes, String materialLink) {}
    private record CurriculumBase(String id, String majorCode, String majorName, String version,
            String name, String description, int majorCredits) {}
    private record CurriculumCourseRow(int semesterNumber, String courseCode, int credits) {}

    public record LookupPrerequisite(String courseCode, String courseName, int groupNo, BigDecimal minimumScore) {}
    public record SyllabusWeek(int week, String topic, String content) {}
    public record LookupCourse(String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes, String materialLink,
            List<LookupPrerequisite> prerequisites, List<SyllabusWeek> syllabus) {
        public LookupCourse(String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes,
                List<LookupPrerequisite> prerequisites, List<SyllabusWeek> syllabus) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes, null, prerequisites, syllabus);
        }
    }
    public record LookupSemester(int semesterNumber, List<String> courses) {}
    public record LookupCurriculum(String id, String majorCode, String majorName, String version,
            String name, String description, int totalCredits, List<LookupSemester> semesters) {}
}
