package vn.edu.fpt.faaps.admin;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminMajorService {
    private final JdbcTemplate jdbc;

    public AdminMajorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String cohort, String status, String search, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.equalsIgnoreCase("ALL") && !List.of("ACTIVE", "INACTIVE", "DRAFT").contains(status.toUpperCase())) {
            throw new IllegalArgumentException("status is invalid");
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND m.status = ?");
            params.add(status.toUpperCase());
        }

        if (cohort != null && !cohort.isBlank() && !cohort.equalsIgnoreCase("ALL")) {
            where.append(" AND EXISTS (SELECT 1 FROM curriculum_versions cv WHERE cv.major_id = m.id AND cv.version = ?)");
            params.add(cohort.strip());
        }

        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(m.code) LIKE ? OR LOWER(m.name) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            params.add(term);
            params.add(term);
        }

        String countSql = "SELECT COUNT(*) FROM majors m" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total == null ? 0 : total;

        String orderBy = " ORDER BY m.created_at DESC, m.id DESC";
        if (sort != null && !sort.isBlank()) {
            orderBy = switch (sort) {
                case "code,asc" -> " ORDER BY m.code ASC, m.id ASC";
                case "code,desc" -> " ORDER BY m.code DESC, m.id DESC";
                case "name,asc" -> " ORDER BY m.name ASC, m.id ASC";
                case "name,desc" -> " ORDER BY m.name DESC, m.id DESC";
                case "createdAt,asc" -> " ORDER BY m.created_at ASC, m.id ASC";
                default -> " ORDER BY m.created_at DESC, m.id DESC";
            };
        }

        String sql = """
                SELECT m.id, m.code, m.name, m.description, m.total_credits, m.duration_years,
                       m.status, m.created_at, m.updated_at,
                       (SELECT cv.version FROM curriculum_versions cv WHERE cv.major_id = m.id ORDER BY cv.created_at DESC LIMIT 1) AS latest_cohort
                FROM majors m
                """ + where + orderBy + " LIMIT ? OFFSET ?";

        params.add(size);
        params.add(page * size);

        List<MajorItem> items = jdbc.query(sql, (rs, rowNum) -> mapMajorItem(rs), params.toArray());
        long totalPages = (totalElements + size - 1) / size;
        PageData pageData = new PageData(items, page, size, totalElements, totalPages);
        return new Page(items, page, size, size, totalElements, totalElements, totalPages, pageData, true, "Success");
    }

    public MajorItem detail(UUID id) {
        String sql = """
                SELECT m.id, m.code, m.name, m.description, m.total_credits, m.duration_years,
                       m.status, m.created_at, m.updated_at,
                       (SELECT cv.version FROM curriculum_versions cv WHERE cv.major_id = m.id ORDER BY cv.created_at DESC LIMIT 1) AS latest_cohort
                FROM majors m
                WHERE m.id = ?
                """;
        List<MajorItem> results = jdbc.query(sql, (rs, rowNum) -> mapMajorItem(rs), id.toString());
        if (results.isEmpty()) {
            throw new NotFound();
        }
        return results.get(0);
    }

    @Transactional
    public MajorItem create(String code, String name, String description, int totalCredits,
            BigDecimal durationYears, String status, String cohort) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("code and name are required");
        }
        if (totalCredits <= 0) {
            throw new IllegalArgumentException("totalCredits must be positive");
        }
        if (durationYears == null || durationYears.compareTo(BigDecimal.ZERO) <= 0) {
            durationYears = new BigDecimal("4.0");
        }
        String majorStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE", "DRAFT").contains(majorStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO majors (id, code, name, description, total_credits, duration_years, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    id.toString(), code.strip(), name.strip(), description, totalCredits, durationYears, majorStatus);
        } catch (DuplicateKeyException e) {
            throw new Conflict("Major code already exists");
        }

        if (cohort != null && !cohort.isBlank()) {
            try {
                jdbc.update("""
                        INSERT INTO curriculum_versions (id, major_id, version, name, is_current, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, true, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                        UUID.randomUUID().toString(), id.toString(), cohort.strip(), name.strip() + " - " + cohort.strip());
            } catch (Exception ignored) {
            }
        }

        return detail(id);
    }

    @Transactional
    public MajorItem update(UUID id, String code, String name, String description,
            Integer totalCredits, BigDecimal durationYears, String status) {
        MajorItem current = detail(id);
        String newCode = (code != null && !code.isBlank()) ? code.strip() : current.code();
        String newName = (name != null && !name.isBlank()) ? name.strip() : current.name();
        String newDesc = description != null ? description : current.description();
        int newCredits = (totalCredits != null && totalCredits > 0) ? totalCredits : current.totalCredits();
        BigDecimal newDuration = (durationYears != null && durationYears.compareTo(BigDecimal.ZERO) > 0)
                ? durationYears : current.durationYears();
        String newStatus = current.status();
        if (status != null && !status.isBlank()) {
            String upper = status.toUpperCase();
            if (List.of("ACTIVE", "INACTIVE", "DRAFT").contains(upper)) {
                newStatus = upper;
            }
        }

        try {
            jdbc.update("""
                    UPDATE majors
                    SET code = ?, name = ?, description = ?, total_credits = ?, duration_years = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    newCode, newName, newDesc, newCredits, newDuration, newStatus, id.toString());
        } catch (DuplicateKeyException e) {
            throw new Conflict("Major code already exists");
        }

        return detail(id);
    }

    @Transactional
    public void delete(UUID id) {
        detail(id);
        try {
            Integer studentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE major_id = ?", Integer.class, id.toString());
            if (studentCount != null && studentCount > 0) {
                throw new Conflict("Cannot delete major because " + studentCount + " student(s) are assigned to it");
            }
            Integer curriculumCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curriculum_versions WHERE major_id = ?", Integer.class, id.toString());
            if (curriculumCount != null && curriculumCount > 0) {
                throw new Conflict("Cannot delete major because " + curriculumCount + " curriculum version(s) reference it");
            }
            jdbc.update("DELETE FROM majors WHERE id = ?", id.toString());
        } catch (Conflict c) {
            throw c;
        } catch (DataIntegrityViolationException e) {
            throw new Conflict("Cannot delete major due to existing references");
        }
    }

    @Transactional
    public int bulkDelete(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            delete(id);
            deleted++;
        }
        return deleted;
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
                rs.getString("latest_cohort") != null ? rs.getString("latest_cohort") : "K18",
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null
        );
    }

    public record MajorItem(UUID id, String code, String name, String description, int totalCredits,
            BigDecimal durationYears, String status, String cohort, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record PageData(List<MajorItem> items, int page, int pageSize, long totalItems, long totalPages) {
    }

    public record Page(List<MajorItem> items, int page, int size, int pageSize, long totalElements,
            long totalItems, long totalPages, PageData data, boolean success, String message) {
    }

    public static class NotFound extends RuntimeException {
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
