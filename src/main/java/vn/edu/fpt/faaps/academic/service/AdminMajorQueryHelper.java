package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminMajorService.MajorItem;
import vn.edu.fpt.faaps.academic.service.AdminMajorService.NotFound;
import vn.edu.fpt.faaps.academic.service.AdminMajorService.Page;
import vn.edu.fpt.faaps.academic.service.AdminMajorService.PageData;

final class AdminMajorQueryHelper {
    private static final String BASE_SELECT = """
            SELECT m.id, m.code, m.name, m.description, m.total_credits, m.duration_years,
                   m.status, m.created_at, m.updated_at,
                   (SELECT cv.version FROM curriculum_versions cv
                    WHERE cv.major_id = m.id
                    ORDER BY cv.updated_at DESC, cv.created_at DESC LIMIT 1) AS latest_cohort
            FROM majors m
            """;

    private final JdbcTemplate jdbc;

    AdminMajorQueryHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page list(String code, BigDecimal durationYears, String cohort, String status,
            String search, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL")
                && !List.of("ACTIVE", "INACTIVE", "DRAFT").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (code != null && !code.isBlank() && !code.equalsIgnoreCase("ALL")) {
            where.append(" AND UPPER(m.code) = ?");
            params.add(code.strip().toUpperCase());
        }
        if (durationYears != null) {
            where.append(" AND m.duration_years = ?");
            params.add(durationYears);
        }
        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND m.status = ?");
            params.add(status.toUpperCase());
        }
        if (cohort != null && !cohort.isBlank() && !cohort.equalsIgnoreCase("ALL")) {
            where.append(" AND EXISTS (SELECT 1 FROM curriculum_versions cv"
                    + " WHERE cv.major_id = m.id AND cv.version = ?)");
            params.add(cohort.strip());
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(m.code) LIKE ? OR LOWER(m.name) LIKE ?"
                    + " OR CAST(m.total_credits AS CHAR) LIKE ? OR CAST(m.duration_years AS CHAR) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM majors m" + where, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;
        String sql = BASE_SELECT + where + resolveOrderBy(sort) + " LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);

        List<MajorItem> items = jdbc.query(sql, (rs, rowNum) -> mapMajorItem(rs), params.toArray());
        long totalPages = (totalElements + size - 1) / size;
        PageData pageData = new PageData(items, page, size, totalElements, totalPages);
        return new Page(items, page, size, size, totalElements, totalElements, totalPages, pageData, true, "Success");
    }

    MajorItem detail(UUID id) {
        List<MajorItem> results = jdbc.query(BASE_SELECT + " WHERE m.id = ?",
                (rs, rowNum) -> mapMajorItem(rs), id.toString());
        if (results.isEmpty()) {
            throw new NotFound();
        }
        return results.get(0);
    }

    private String resolveOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return " ORDER BY m.created_at DESC, m.id DESC";
        }
        return switch (sort) {
            case "code,asc" -> " ORDER BY m.code ASC, m.id ASC";
            case "code,desc" -> " ORDER BY m.code DESC, m.id DESC";
            case "name,asc" -> " ORDER BY m.name ASC, m.id ASC";
            case "name,desc" -> " ORDER BY m.name DESC, m.id DESC";
            case "createdAt,asc" -> " ORDER BY m.created_at ASC, m.id ASC";
            default -> " ORDER BY m.created_at DESC, m.id DESC";
        };
    }

    private MajorItem mapMajorItem(ResultSet rs) throws SQLException {
        return new MajorItem(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("total_credits"),
                rs.getBigDecimal("duration_years"),
                rs.getString("status"),
                rs.getString("latest_cohort") != null ? rs.getString("latest_cohort") : "",
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
    }
}
