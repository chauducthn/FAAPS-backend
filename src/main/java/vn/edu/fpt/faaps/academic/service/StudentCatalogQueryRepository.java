package vn.edu.fpt.faaps.academic.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService.*;

final class StudentCatalogQueryRepository {
    private final JdbcTemplate jdbc;

    StudentCatalogQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Course> courses(String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        String term = "%" + (search == null ? "" : search.strip().toLowerCase()) + "%";
        try {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, c.description, c.objectives,
                           c.learning_outcomes, c.material_link, cat.name AS category_name
                    FROM courses c JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.status = 'ACTIVE' AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)
                    ORDER BY c.code LIMIT ? OFFSET ?
                    """, (rs, row) -> mapCourse(rs), term, term, size, page * size);
        } catch (Exception ex) {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, c.description, c.objectives,
                           c.learning_outcomes, NULL AS material_link, cat.name AS category_name
                    FROM courses c JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.status = 'ACTIVE' AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)
                    ORDER BY c.code LIMIT ? OFFSET ?
                    """, (rs, row) -> mapCourse(rs), term, term, size, page * size);
        }
    }

    CoursePage coursesPaged(String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        String term = "%" + (search == null ? "" : search.strip().toLowerCase()) + "%";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM courses c JOIN course_categories cat ON cat.id = c.category_id
                WHERE c.status = 'ACTIVE' AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)
                """, Long.class, term, term);
        long totalElements = total == null ? 0 : total;
        return new CoursePage(courses(search, page, size), page, size, totalElements, (totalElements + size - 1) / size);
    }

    Course course(UUID id) {
        try {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, c.description, c.objectives,
                           c.learning_outcomes, c.material_link, cat.name AS category_name
                    FROM courses c JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.id = ? AND c.status = 'ACTIVE'
                    """, (rs, row) -> mapCourse(rs), id.toString()).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found"));
        } catch (ResponseStatusException notFound) {
            throw notFound;
        } catch (Exception ex) {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, c.description, c.objectives,
                           c.learning_outcomes, NULL AS material_link, cat.name AS category_name
                    FROM courses c JOIN course_categories cat ON cat.id = c.category_id
                    WHERE c.id = ? AND c.status = 'ACTIVE'
                    """, (rs, row) -> mapCourse(rs), id.toString()).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found"));
        }
    }

    List<Prerequisite> prerequisites(UUID courseId) {
        course(courseId);
        return jdbc.query("""
                SELECT p.id, c.code, c.name, p.group_no, p.type, p.minimum_score
                FROM course_prerequisites p JOIN courses c ON c.id = p.prerequisite_course_id
                WHERE p.course_id = ? ORDER BY p.group_no, c.code
                """, (rs, row) -> new Prerequisite(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("group_no"), rs.getString("type"),
                rs.getBigDecimal("minimum_score")), courseId.toString());
    }

    Curriculum curriculum(String curriculumVersionId) {
        if (curriculumVersionId == null) {
            return null;
        }
        return jdbc.query("""
                SELECT cv.id, cv.version, cv.name, cv.description, m.code AS major_code, m.name AS major_name
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id WHERE cv.id = ?
                """, (rs, row) -> new Curriculum(rs.getString("id"), rs.getString("version"),
                rs.getString("name"), rs.getString("description"), rs.getString("major_code"),
                rs.getString("major_name")), curriculumVersionId).stream().findFirst().orElse(null);
    }

    List<CurriculumCourse> curriculumCourses(Curriculum c) {
        if (c == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT cc.course_id, co.code, co.name, co.credits, cc.semester_number, cc.is_mandatory, cc.group_name
                FROM curriculum_courses cc JOIN courses co ON co.id = cc.course_id
                WHERE cc.curriculum_version_id = ? ORDER BY cc.semester_number, co.code
                """, (rs, row) -> new CurriculumCourse(rs.getString("course_id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("credits"), rs.getInt("semester_number"),
                rs.getBoolean("is_mandatory"), rs.getString("group_name")), c.id());
    }

    private Course mapCourse(ResultSet rs) throws SQLException {
        return new Course(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("credits"), rs.getString("category_name"), rs.getString("description"),
                rs.getString("objectives"), rs.getString("learning_outcomes"), rs.getString("material_link"));
    }
}
