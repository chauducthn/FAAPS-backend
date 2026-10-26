package vn.edu.fpt.faaps.user.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Summary;
import vn.edu.fpt.faaps.user.service.UserExceptions.NotFound;

@Repository
public class AdminUserRepository {
    private final JdbcTemplate jdbc;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        String order = switch (sort) {
            case "createdAt,asc" -> "u.created_at ASC, u.id ASC";
            case "createdAt,desc" -> "u.created_at DESC, u.id DESC";
            case "fullName,asc" -> "u.full_name ASC, u.id ASC";
            case "fullName,desc" -> "u.full_name DESC, u.id DESC";
            case "userId,asc" -> "COALESCE(s.student_code, u.email) ASC, u.id ASC";
            case "userId,desc" -> "COALESCE(s.student_code, u.email) DESC, u.id DESC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
        StringBuilder where = new StringBuilder(" WHERE r.code IN ('STUDENT', 'ADVISOR')");
        List<Object> parameters = new ArrayList<>();
        addFilter(where, parameters, " AND r.code = ?", role);
        addFilter(where, parameters, " AND u.status = ?", status);
        addFilter(where, parameters, " AND s.major_id = ?", majorId == null ? null : majorId.toString());
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(s.student_code) LIKE ?)");
            parameters.addAll(List.of(term, term, term));
        }
        String from = " FROM users u JOIN roles r ON r.id=u.role_id LEFT JOIN students s ON s.user_id=u.id";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        List<Object> paging = new ArrayList<>(parameters);
        paging.add(size);
        paging.add(page * size);
        List<Summary> items = jdbc.query("SELECT u.id, CASE WHEN r.code='STUDENT' THEN COALESCE(s.student_code,u.email) ELSE u.email END user_code, u.full_name,u.email,r.code role,u.status,u.created_at" + from + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", this::summary, paging.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Detail detail(UUID id) {
        return jdbc.query("""
                SELECT u.id, CASE WHEN r.code='STUDENT' THEN COALESCE(s.student_code,u.email) ELSE u.email END user_code,
                       u.full_name,u.email,r.code role,u.status,u.created_at,u.updated_at,s.major_id,m.name major_name,
                       s.curriculum_version_id,cv.version curriculum_code,s.enrollment_semester_id,
                       s.expected_graduation_semester_id,s.current_semester,s.academic_status
                FROM users u JOIN roles r ON r.id=u.role_id LEFT JOIN students s ON s.user_id=u.id
                LEFT JOIN majors m ON m.id=s.major_id LEFT JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id
                WHERE u.id=? AND r.code IN ('STUDENT','ADVISOR')
                """, (rs, row) -> new Detail(UUID.fromString(rs.getString("id")), rs.getString("user_code"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getString("major_id"), rs.getString("major_name"), rs.getString("curriculum_version_id"),
                rs.getString("curriculum_code"), rs.getString("enrollment_semester_id"),
                rs.getString("expected_graduation_semester_id"), (Integer) rs.getObject("current_semester"),
                rs.getString("academic_status")), id.toString()).stream().findFirst().orElseThrow(NotFound::new);
    }

    private void addFilter(StringBuilder where, List<Object> values, String sql, Object value) {
        if (value != null) { where.append(sql); values.add(value); }
    }

    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(UUID.fromString(rs.getString("id")), rs.getString("user_code"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("role"),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
