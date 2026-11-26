package vn.edu.fpt.faaps.academic.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseDetail;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseSummary;

final class CourseReadMapper {
    static final String SUMMARY_SELECT = """
            SELECT c.id, c.code, c.name, c.credits, c.category_id,
                   cat.code AS category_code, cat.name AS category_name,
                   c.description, c.objectives, c.learning_outcomes, %s,
                   c.status, c.created_at, c.updated_at,
                   (SELECT COUNT(*) FROM course_prerequisites cp WHERE cp.course_id = c.id) AS prerequisite_count,
                   (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.course_id = c.id) AS curriculum_count
            FROM courses c
            LEFT JOIN course_categories cat ON cat.id = c.category_id
            """;

    static final String DETAIL_SELECT = """
            SELECT c.id, c.code, c.name, c.credits, c.category_id,
                   cat.code AS category_code, cat.name AS category_name,
                   c.description, c.objectives, c.learning_outcomes, %s,
                   c.status, c.created_at, c.updated_at,
                   (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.course_id = c.id) AS curriculum_count
            FROM courses c
            LEFT JOIN course_categories cat ON cat.id = c.category_id
            WHERE c.id = ?
            """;

    private CourseReadMapper() {
    }

    static String resolveOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return " ORDER BY c.code ASC, c.id ASC";
        }
        return switch (sort) {
            case "code,asc" -> " ORDER BY c.code ASC, c.id ASC";
            case "code,desc" -> " ORDER BY c.code DESC, c.id DESC";
            case "name,asc" -> " ORDER BY c.name ASC, c.id ASC";
            case "name,desc" -> " ORDER BY c.name DESC, c.id DESC";
            case "credits,asc" -> " ORDER BY c.credits ASC, c.code ASC";
            case "credits,desc" -> " ORDER BY c.credits DESC, c.code ASC";
            case "categoryCode,asc" -> " ORDER BY COALESCE(cat.code, 'ZZZZ') ASC, c.code ASC";
            case "categoryCode,desc" -> " ORDER BY COALESCE(cat.code, '') DESC, c.code ASC";
            case "categoryName,asc" -> " ORDER BY COALESCE(cat.name, 'ZZZZ') ASC, c.code ASC";
            case "categoryName,desc" -> " ORDER BY COALESCE(cat.name, '') DESC, c.code ASC";
            case "prerequisiteCount,asc" -> " ORDER BY prerequisite_count ASC, c.code ASC";
            case "prerequisiteCount,desc" -> " ORDER BY prerequisite_count DESC, c.code ASC";
            case "status,asc" -> " ORDER BY c.status ASC, c.code ASC";
            case "status,desc" -> " ORDER BY c.status DESC, c.code ASC";
            case "createdAt,desc" -> " ORDER BY c.created_at DESC, c.id DESC";
            case "createdAt,asc" -> " ORDER BY c.created_at ASC, c.id ASC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
    }

    static CourseSummary mapSummary(ResultSet rs) throws SQLException {
        return new CourseSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("credits"),
                rs.getString("category_id") != null ? UUID.fromString(rs.getString("category_id")) : null,
                rs.getString("category_code"),
                rs.getString("category_name"),
                rs.getString("description"),
                rs.getString("material_link"),
                rs.getString("status"),
                rs.getInt("prerequisite_count"),
                rs.getInt("curriculum_count"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
    }

    static CourseDetail mapDetail(ResultSet rs) throws SQLException {
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
                rs.getString("material_link"),
                rs.getString("status"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null,
                List.of(),
                List.of(),
                rs.getInt("curriculum_count"));
    }
}
