package vn.edu.fpt.faaps.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private final JdbcTemplate jdbc;

    public AdminUserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (role != null && !List.of("STUDENT", "ADVISOR", "ADMIN").contains(role)) {
            throw new IllegalArgumentException("role is invalid");
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "SUSPENDED", "LOCKED").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        String order = switch (sort) {
            case "createdAt,asc" -> "u.created_at ASC, u.id ASC";
            case "createdAt,desc" -> "u.created_at DESC, u.id DESC";
            case "fullName,asc" -> "u.full_name ASC, u.id ASC";
            case "fullName,desc" -> "u.full_name DESC, u.id DESC";
            case "userId,asc" -> "COALESCE(s.student_code, a.advisor_code, u.email) ASC, u.id ASC";
            case "userId,desc" -> "COALESCE(s.student_code, a.advisor_code, u.email) DESC, u.id DESC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (role != null) {
            where.append(" AND r.code = ?");
            parameters.add(role);
        }
        if (status != null) {
            where.append(" AND u.status = ?");
            parameters.add(status);
        }
        if (majorId != null) {
            where.append(" AND s.major_id = ?");
            parameters.add(majorId.toString());
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(s.student_code) LIKE ? OR LOWER(a.advisor_code) LIKE ?)");
            for (int index = 0; index < 4; index++) {
                parameters.add(term);
            }
        }
        String from = " FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN students s ON s.user_id = u.id LEFT JOIN advisors a ON a.user_id = u.id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(page * size);
        List<Summary> items = jdbc.query("SELECT u.id, COALESCE(s.student_code, a.advisor_code, u.email) AS user_code, u.full_name, u.email, r.code AS role, u.status, u.created_at" + from + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", this::summary, pageParameters.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Detail detail(UUID id) {
        return jdbc.query("""
                SELECT u.id, COALESCE(s.student_code, a.advisor_code, u.email) AS user_code,
                       u.full_name, u.email, u.phone, r.code AS role, u.status,
                       u.created_at, u.updated_at, s.major_id, m.name AS major_name,
                       s.curriculum_version_id, cv.version AS curriculum_code,
                       s.enrollment_semester_id,
                       s.current_semester, s.academic_status, a.advisor_code
                FROM users u JOIN roles r ON r.id = u.role_id
                LEFT JOIN students s ON s.user_id = u.id
                LEFT JOIN advisors a ON a.user_id = u.id
                LEFT JOIN majors m ON m.id = s.major_id
                LEFT JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                WHERE u.id = ?
                """, (result, row) -> new Detail(
                UUID.fromString(result.getString("id")), result.getString("user_code"),
                result.getString("full_name"), result.getString("email"), result.getString("phone"),
                result.getString("role"), result.getString("status"),
                result.getTimestamp("created_at").toLocalDateTime(),
                result.getTimestamp("updated_at").toLocalDateTime(),
                result.getString("major_id"), result.getString("major_name"),
                result.getString("curriculum_version_id"), result.getString("curriculum_code"),
                result.getString("enrollment_semester_id"),
                (Integer) result.getObject("current_semester"), result.getString("academic_status"),
                result.getString("advisor_code")), id.toString()).stream().findFirst().orElseThrow(NotFound::new);
    }

    @Transactional
    public int changeStatus(List<UUID> userIds, String status, UUID actorId) {
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        }
        if (userIds.size() > 100 || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("userIds must be unique and contain at most 100 users");
        }
        if ("SUSPENDED".equals(status) && userIds.contains(actorId)) {
            throw new Conflict();
        }
        int changed = 0;
        for (UUID id : userIds) {
            List<String> current = jdbc.query("SELECT status FROM users WHERE id = ? FOR UPDATE",
                    (result, row) -> result.getString(1), id.toString());
            if (current.isEmpty()) {
                throw new NotFound();
            }
            if (status.equals(current.get(0))) {
                continue;
            }
            if (!("ACTIVE".equals(current.get(0)) && "SUSPENDED".equals(status))
                    && !("SUSPENDED".equals(current.get(0)) && "ACTIVE".equals(status))) {
                throw new Conflict();
            }
            changed += jdbc.update("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    status, id.toString());
        }
        return changed;
    }

    private Summary summary(ResultSet result, int row) throws SQLException {
        return new Summary(UUID.fromString(result.getString("id")), result.getString("user_code"),
                result.getString("full_name"), result.getString("email"),
                result.getString("role"), result.getString("status"),
                result.getTimestamp("created_at").toLocalDateTime());
    }

    public record Summary(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt) {
    }

    public record Detail(UUID id, String userId, String fullName, String email, String phone,
            String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            String majorId, String majorName, String curriculumVersionId, String curriculumCode,
            String enrollmentSemesterId,
            Integer currentSemester, String academicStatus, String advisorCode) {
    }

    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
    }

    public static class Conflict extends RuntimeException {
    }
}
