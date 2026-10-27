package vn.edu.fpt.faaps.repository;

import vn.edu.fpt.faaps.service.AdminCourseService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.service.AdminCourseService.CourseDetail;
import vn.edu.fpt.faaps.service.AdminCourseService.CourseReference;
import vn.edu.fpt.faaps.service.AdminCourseService.CourseSummary;
import vn.edu.fpt.faaps.service.AdminCourseService.NotFound;
import vn.edu.fpt.faaps.service.AdminCourseService.Page;
import vn.edu.fpt.faaps.service.AdminCourseService.PrerequisiteItem;

public final class CourseReadRepository {
    private final JdbcTemplate jdbc;
    public CourseReadRepository(JdbcTemplate jdbc) {
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
                       (SELECT COUNT(*) FROM course_prerequisites cp WHERE cp.course_id = c.id) AS prerequisite_count,
                       (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.course_id = c.id) AS curriculum_count
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
                       c.status, c.created_at, c.updated_at,
                       (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.course_id = c.id) AS curriculum_count
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

        List<CourseReference> referencedBy = jdbc.query("""
                SELECT c.id, c.code, c.name
                FROM course_prerequisites cp
                JOIN courses c ON c.id = cp.course_id
                WHERE cp.prerequisite_course_id = ? AND cp.type = 'PREREQUISITE'
                ORDER BY c.code ASC, c.id ASC
                """, (rs, rowNum) -> new CourseReference(UUID.fromString(rs.getString("id")),
                rs.getString("code"), rs.getString("name")), id.toString());

        return new CourseDetail(base.id(), base.code(), base.name(), base.credits(),
                base.categoryId(), base.categoryCode(), base.categoryName(),
                base.description(), base.objectives(), base.learningOutcomes(),
                base.status(), base.createdAt(), base.updatedAt(), prerequisites,
                referencedBy, base.usedInCurriculumCount());
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
                rs.getInt("curriculum_count"),
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
                List.of(), List.of(), rs.getInt("curriculum_count")
        );
    }

}


