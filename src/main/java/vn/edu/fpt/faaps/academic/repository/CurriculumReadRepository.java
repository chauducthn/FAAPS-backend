package vn.edu.fpt.faaps.academic.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCurriculumService;

public class CurriculumReadRepository {
    private static final String BASE_SELECT = """
            SELECT cv.id, cv.major_id, m.code major_code, m.name major_name,
                   cv.version, cv.name, cv.effective_from_semester_id,
                   sf.code effective_from_semester_code, sf.start_date effective_from_date,
                   cv.effective_to_semester_id, st.code effective_to_semester_code,
                   st.end_date effective_to_date, cv.description,
                   COALESCE(cv.is_current, FALSE) is_current, cv.status,
                   (SELECT COUNT(*) FROM curriculum_courses cc WHERE cc.curriculum_version_id = cv.id) course_count,
                   (SELECT COALESCE(SUM(c.credits), 0)
                    FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id
                    WHERE cc.curriculum_version_id = cv.id AND cc.semester_number > 0
                      AND UPPER(c.code) NOT REGEXP '^(PHE|VOV|GDQP|OTP|TMI|TPI|PEN|TRS|LUK)') total_credits,
                   COALESCE(m.total_credits, 0) major_total_credits,
                   cv.created_at, cv.updated_at
            FROM curriculum_versions cv
            JOIN majors m ON m.id = cv.major_id
            JOIN semesters sf ON sf.id = cv.effective_from_semester_id
            LEFT JOIN semesters st ON st.id = cv.effective_to_semester_id
            """;

    private final JdbcTemplate jdbc;

    public CurriculumReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminCurriculumService.Page list(
            UUID major, String majorCode, String version, UUID startSemesterId,
            String status, Boolean current, String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        String normalized = normalize(status);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (major != null) {
            where.append(" AND cv.major_id = ?");
            args.add(major.toString());
        }
        if (majorCode != null && !majorCode.isBlank() && !"ALL".equalsIgnoreCase(majorCode)) {
            where.append(" AND UPPER(m.code) = ?");
            args.add(majorCode.strip().toUpperCase());
        }
        if (version != null && !version.isBlank() && !"ALL".equalsIgnoreCase(version)) {
            where.append(" AND UPPER(cv.version) = ?");
            args.add(version.strip().toUpperCase());
        }
        if (startSemesterId != null) {
            where.append(" AND cv.effective_from_semester_id = ?");
            args.add(startSemesterId.toString());
        }
        if (normalized != null) {
            where.append(" AND cv.status = ?");
            args.add(normalized);
        }
        if (current != null) {
            where.append(" AND COALESCE(cv.is_current, FALSE) = ?");
            args.add(current);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(cv.version) LIKE ? OR LOWER(cv.name) LIKE ?"
                    + " OR LOWER(m.code) LIKE ? OR LOWER(m.name) LIKE ?"
                    + " OR LOWER(sf.code) LIKE ? OR LOWER(COALESCE(st.code, '')) LIKE ?"
                    + " OR CONCAT(LOWER(m.code), ' ', LOWER(cv.version)) LIKE ?"
                    + " OR CONCAT(LOWER(m.code), LOWER(SUBSTRING(cv.version, 2))) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            args.addAll(List.of(term, term, term, term, term, term, term, term));
        }
        String countSql = "SELECT COUNT(*) FROM curriculum_versions cv"
                + " JOIN majors m ON m.id = cv.major_id"
                + " JOIN semesters sf ON sf.id = cv.effective_from_semester_id"
                + " LEFT JOIN semesters st ON st.id = cv.effective_to_semester_id" + where;
        long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        List<Object> paging = new ArrayList<>(args);
        paging.add(size);
        paging.add((long) page * size);
        String querySql = BASE_SELECT + where + " ORDER BY m.code, cv.version, cv.id LIMIT ? OFFSET ?";
        var items = jdbc.query(querySql, this::map, paging.toArray());
        return new AdminCurriculumService.Page(items, page, size, total, (total + size - 1) / size);
    }

    public AdminCurriculumService.CurriculumItem detail(UUID id) {
        return jdbc.query(BASE_SELECT + " WHERE cv.id = ?", this::map, id.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new AdminCurriculumService.NotFound("Curriculum or cohort not found"));
    }

    public List<AdminCurriculumService.CurriculumCourseItem> courses(UUID id) {
        detail(id);
        return jdbc.query("""
                SELECT cc.id, cc.curriculum_version_id, cc.course_id, c.code course_code,
                       c.name course_name, c.credits, c.status course_status, cc.semester_number,
                       COALESCE(cc.is_mandatory, TRUE) is_mandatory, cc.group_name, cc.created_at, cc.updated_at
                FROM curriculum_courses cc
                JOIN courses c ON c.id = cc.course_id
                WHERE cc.curriculum_version_id = ?
                ORDER BY cc.semester_number, c.code, cc.id
                """, (rs, row) -> new AdminCurriculumService.CurriculumCourseItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("curriculum_version_id")),
                UUID.fromString(rs.getString("course_id")),
                rs.getString("course_code"),
                rs.getString("course_name"),
                rs.getInt("credits"),
                rs.getString("course_status"),
                rs.getInt("semester_number"),
                rs.getBoolean("is_mandatory"),
                rs.getString("group_name"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()), id.toString());
    }

    private String normalize(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        String normalized = status.strip().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
        }
        return normalized;
    }

    private AdminCurriculumService.CurriculumItem map(ResultSet rs, int row) throws SQLException {
        String effectiveToId = rs.getString("effective_to_semester_id");
        return new AdminCurriculumService.CurriculumItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("major_id")),
                rs.getString("major_code"),
                rs.getString("major_name"),
                rs.getString("version"),
                rs.getString("name"),
                UUID.fromString(rs.getString("effective_from_semester_id")),
                rs.getString("effective_from_semester_code"),
                date(rs, "effective_from_date"),
                effectiveToId == null ? null : UUID.fromString(effectiveToId),
                rs.getString("effective_to_semester_code"),
                date(rs, "effective_to_date"),
                rs.getString("description"),
                rs.getBoolean("is_current"),
                rs.getString("status"),
                rs.getInt("course_count"),
                rs.getInt("total_credits"),
                rs.getInt("major_total_credits"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private LocalDate date(ResultSet rs, String column) throws SQLException {
        var value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
