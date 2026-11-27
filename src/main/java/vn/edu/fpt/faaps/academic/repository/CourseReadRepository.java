package vn.edu.fpt.faaps.academic.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseDetail;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseReference;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.CourseSummary;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.NotFound;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.Page;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.PrerequisiteItem;

public final class CourseReadRepository {
    private final JdbcTemplate jdbc;

    public CourseReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String search, String status, UUID categoryId, int page, int size, String sort) {
        return list(search, status, categoryId, null, page, size, sort);
    }

    public Page list(String search, String status, UUID categoryId, Integer credits, int page, int size, String sort) {
        validateListParams(status, credits, page, size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilters(where, params, search, status, categoryId, credits);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM courses c" + where, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;
        String suffix = where + CourseReadMapper.resolveOrderBy(sort) + " LIMIT ? OFFSET ?";
        String sqlWithLink = CourseReadMapper.SUMMARY_SELECT.formatted("c.material_link") + suffix;
        String sqlLegacy = CourseReadMapper.SUMMARY_SELECT.formatted("NULL AS material_link") + suffix;

        params.add(size);
        params.add(page * size);
        List<CourseSummary> items;
        try {
            items = jdbc.query(sqlWithLink, (rs, rowNum) -> CourseReadMapper.mapSummary(rs), params.toArray());
        } catch (Exception ex) {
            items = jdbc.query(sqlLegacy, (rs, rowNum) -> CourseReadMapper.mapSummary(rs), params.toArray());
        }
        return new Page(items, page, size, totalElements, (totalElements + size - 1) / size);
    }

    public CourseDetail detail(UUID id) {
        String sqlWithExtra = CourseReadMapper.DETAIL_SELECT.formatted(
                "c.material_link, c.knowledge_block, c.grading_structure");
        String sqlLegacy = CourseReadMapper.DETAIL_SELECT.formatted(
                "NULL AS material_link, NULL AS knowledge_block, NULL AS grading_structure");
        List<CourseDetail> results;
        try {
            results = jdbc.query(sqlWithExtra, (rs, rowNum) -> CourseReadMapper.mapDetail(rs), id.toString());
        } catch (Exception ex) {
            results = jdbc.query(sqlLegacy, (rs, rowNum) -> CourseReadMapper.mapDetail(rs), id.toString());
        }
        if (results.isEmpty()) {
            throw new NotFound();
        }
        CourseDetail base = results.get(0);
        List<PrerequisiteItem> prerequisites = loadPrerequisites(id);
        List<CourseReference> referencedBy = loadReferencedBy(id);
        return new CourseDetail(base.id(), base.code(), base.name(), base.credits(),
                base.categoryId(), base.categoryCode(), base.categoryName(),
                base.description(), base.objectives(), base.learningOutcomes(),
                base.materialLink(), base.knowledgeBlock(), base.gradingStructure(),
                base.status(), base.createdAt(), base.updatedAt(),
                prerequisites, referencedBy, base.usedInCurriculumCount());
    }

    private void validateListParams(String status, Integer credits, int page, int size) {
        if (page < 0 || size < 1 || size > 10000) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL")
                && !List.of("ACTIVE", "INACTIVE").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }
        if (credits != null && credits < 0) {
            throw new IllegalArgumentException("credits must be non-negative");
        }
    }

    private void appendFilters(StringBuilder where, List<Object> params,
            String search, String status, UUID categoryId, Integer credits) {
        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND c.status = ?");
            params.add(status.toUpperCase());
        }
        if (categoryId != null) {
            where.append(" AND c.category_id = ?");
            params.add(categoryId.toString());
        }
        if (credits != null) {
            where.append(" AND c.credits = ?");
            params.add(credits);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(c.code) LIKE ? OR LOWER(c.name) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
        }
    }

    private List<PrerequisiteItem> loadPrerequisites(UUID id) {
        return jdbc.query("""
                SELECT cp.id, cp.prerequisite_course_id, prereq.code AS prerequisite_code,
                       prereq.name AS prerequisite_name, cp.group_no, cp.type, cp.minimum_score
                FROM course_prerequisites cp
                JOIN courses prereq ON prereq.id = cp.prerequisite_course_id
                WHERE cp.course_id = ?
                ORDER BY cp.group_no ASC, prereq.code ASC
                """, (rs, rowNum) -> new PrerequisiteItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("prerequisite_course_id")),
                rs.getString("prerequisite_code"),
                rs.getString("prerequisite_name"),
                rs.getInt("group_no"),
                rs.getString("type"),
                rs.getBigDecimal("minimum_score")), id.toString());
    }

    private List<CourseReference> loadReferencedBy(UUID id) {
        return jdbc.query("""
                SELECT c.id, c.code, c.name
                FROM course_prerequisites cp
                JOIN courses c ON c.id = cp.course_id
                WHERE cp.prerequisite_course_id = ? AND cp.type = 'PREREQUISITE'
                ORDER BY c.code ASC, c.id ASC
                """, (rs, rowNum) -> new CourseReference(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name")), id.toString());
    }
}
