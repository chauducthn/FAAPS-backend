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
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

@Repository
public class AdminUserRepository {
    private final JdbcTemplate jdbc;
    private final boolean hasAvatarColumn;
    private final boolean hasCohortAdvisors;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.hasAvatarColumn = detectQuery(jdbc, "SELECT avatar_url FROM users WHERE 1 = 0");
        this.hasCohortAdvisors = detectQuery(jdbc, "SELECT advisor_id FROM cohort_advisors WHERE 1 = 0")
                && detectQuery(jdbc, "SELECT advisor_id FROM student_advisor_assignments WHERE 1 = 0");
    }

    private static boolean detectQuery(JdbcTemplate jdbc, String sql) {
        try {
            jdbc.query(sql, (rs, row) -> null);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String avatarExpr() {
        return hasAvatarColumn ? "u.avatar_url" : "NULL AS avatar_url";
    }

    private String advisorCohortJoin() {
        if (!hasCohortAdvisors) return "";
        return " LEFT JOIN ("
                + " SELECT adv.advisor_id, MIN(cv2.id) cohort_id, MIN(cv2.major_id) major_id,"
                + " MIN(m2.name) major_name, MIN(cv2.name) curriculum_name,"
                + " GROUP_CONCAT(DISTINCT m2.code ORDER BY m2.code SEPARATOR ', ') major_code,"
                + " GROUP_CONCAT(DISTINCT cv2.version ORDER BY cv2.version SEPARATOR ', ') cohort"
                + " FROM ("
                + "   SELECT advisor_id, cohort_id FROM cohort_advisors WHERE is_active = TRUE"
                + "   UNION"
                + "   SELECT sa.advisor_id, st.curriculum_version_id cohort_id"
                + "   FROM student_advisor_assignments sa JOIN students st ON st.user_id = sa.student_id"
                + "   WHERE sa.status = 'ACTIVE' AND sa.end_date IS NULL AND st.curriculum_version_id IS NOT NULL"
                + " ) adv"
                + " JOIN curriculum_versions cv2 ON cv2.id = adv.cohort_id"
                + " JOIN majors m2 ON m2.id = cv2.major_id"
                + " GROUP BY adv.advisor_id"
                + ") ac ON ac.advisor_id = u.id";
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        return list(role, status, search, majorId, null, page, size, sort);
    }

    public Page list(String role, String status, String search, UUID majorId, UUID curriculumVersionId, int page, int size, String sort) {
        String majorExpr = hasCohortAdvisors ? "COALESCE(m.code, ac.major_code)" : "m.code";
        String cohortExpr = hasCohortAdvisors ? "COALESCE(cv.version, ac.cohort)" : "cv.version";
        String order = switch (sort) {
            case "createdAt,asc" -> "u.created_at ASC, u.id ASC";
            case "createdAt,desc" -> "u.created_at DESC, u.id DESC";
            case "fullName,asc" -> "u.full_name ASC, u.id ASC";
            case "fullName,desc" -> "u.full_name DESC, u.id DESC";
            case "userId,asc" -> "COALESCE(s.student_code, u.email) ASC, u.id ASC";
            case "userId,desc" -> "COALESCE(s.student_code, u.email) DESC, u.id DESC";
            case "email,asc" -> "u.email ASC, u.id ASC";
            case "email,desc" -> "u.email DESC, u.id DESC";
            case "role,asc" -> "r.code ASC, u.full_name ASC, u.id ASC";
            case "role,desc" -> "r.code DESC, u.full_name ASC, u.id DESC";
            case "major,asc" -> "COALESCE(" + majorExpr + ", 'ZZZZ') ASC, u.full_name ASC, u.id ASC";
            case "major,desc" -> "COALESCE(" + majorExpr + ", '') DESC, u.full_name ASC, u.id DESC";
            case "cohort,asc" -> "COALESCE(" + cohortExpr + ", 'ZZZZ') ASC, u.full_name ASC, u.id ASC";
            case "cohort,desc" -> "COALESCE(" + cohortExpr + ", '') DESC, u.full_name ASC, u.id DESC";
            case "status,asc" -> "u.status ASC, u.full_name ASC, u.id ASC";
            case "status,desc" -> "u.status DESC, u.full_name ASC, u.id DESC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
        StringBuilder where = new StringBuilder(" WHERE r.code IN ('STUDENT', 'ADVISOR')");
        List<Object> parameters = new ArrayList<>();
        addFilter(where, parameters, " AND r.code = ?", role);
        addFilter(where, parameters, " AND u.status = ?", status);
        if (majorId != null) {
            if (hasCohortAdvisors) {
                where.append(" AND (s.major_id = ? OR ac.major_id = ?)");
                parameters.add(majorId.toString());
                parameters.add(majorId.toString());
            } else {
                addFilter(where, parameters, " AND s.major_id = ?", majorId.toString());
            }
        }
        if (curriculumVersionId != null) {
            if (hasCohortAdvisors) {
                where.append(" AND (s.curriculum_version_id = ? OR ac.cohort_id = ?)");
                parameters.add(curriculumVersionId.toString());
                parameters.add(curriculumVersionId.toString());
            } else {
                addFilter(where, parameters, " AND s.curriculum_version_id = ?", curriculumVersionId.toString());
            }
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(s.student_code) LIKE ?)");
            parameters.addAll(List.of(term, term, term));
        }
        String from = " FROM users u JOIN roles r ON r.id=u.role_id LEFT JOIN students s ON s.user_id=u.id"
                + " LEFT JOIN majors m ON m.id=s.major_id LEFT JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id"
                + advisorCohortJoin();
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        List<Object> paging = new ArrayList<>(parameters);
        paging.add(size);
        paging.add(page * size);
        List<Summary> items = jdbc.query(
                "SELECT u.id, CASE WHEN r.code='STUDENT' THEN COALESCE(s.student_code,u.email) ELSE u.email END user_code,"
                + " u.full_name, u.email, r.code role, u.status, u.created_at, "
                + majorExpr + " major_code, " + cohortExpr + " cohort, "
                + avatarExpr() + from + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                this::summary, paging.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Detail detail(UUID id) {
        String dMajorId = hasCohortAdvisors ? "COALESCE(s.major_id, cv.major_id, ac.major_id)" : "COALESCE(s.major_id, cv.major_id)";
        String dMajorCode = hasCohortAdvisors ? "COALESCE(m.code, ac.major_code)" : "m.code";
        String dMajorName = hasCohortAdvisors ? "COALESCE(m.name, ac.major_name)" : "m.name";
        String dCvId = hasCohortAdvisors ? "COALESCE(s.curriculum_version_id, ac.cohort_id)" : "s.curriculum_version_id";
        String dCvCode = hasCohortAdvisors ? "COALESCE(cv.version, ac.cohort)" : "cv.version";
        String dCvName = hasCohortAdvisors ? "COALESCE(cv.name, ac.curriculum_name)" : "cv.name";
        return jdbc.query(
                "SELECT u.id, CASE WHEN r.code='STUDENT' THEN COALESCE(s.student_code,u.email) ELSE u.email END user_code,"
                + " u.full_name, u.email, r.code role, u.status, u.created_at, u.updated_at,"
                + " " + dMajorId + " major_id, " + dMajorCode + " major_code, " + dMajorName + " major_name,"
                + " " + dCvId + " curriculum_version_id, " + dCvCode + " curriculum_code, " + dCvName + " curriculum_name,"
                + " s.enrollment_semester_id, es.code enrollment_semester_code,"
                + " s.expected_graduation_semester_id, gs.code expected_graduation_semester_code,"
                + " s.current_semester, s.academic_status, " + avatarExpr()
                + " FROM users u JOIN roles r ON r.id=u.role_id LEFT JOIN students s ON s.user_id=u.id"
                + " LEFT JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id"
                + " LEFT JOIN majors m ON m.id=COALESCE(s.major_id, cv.major_id)"
                + " LEFT JOIN semesters es ON es.id=s.enrollment_semester_id"
                + " LEFT JOIN semesters gs ON gs.id=s.expected_graduation_semester_id"
                + advisorCohortJoin()
                + " WHERE u.id=? AND r.code IN ('STUDENT','ADVISOR')",
                (rs, row) -> new Detail(UUID.fromString(rs.getString("id")),
                resolveIdentifier(rs.getString("role"), rs.getString("user_code"), rs.getString("email")),
                rs.getString("full_name"), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getString("major_id"), rs.getString("major_code"), rs.getString("major_name"),
                rs.getString("curriculum_version_id"), rs.getString("curriculum_code"), rs.getString("curriculum_name"),
                rs.getString("enrollment_semester_id"), rs.getString("enrollment_semester_code"),
                rs.getString("expected_graduation_semester_id"), rs.getString("expected_graduation_semester_code"),
                (Integer) rs.getObject("current_semester"), rs.getString("academic_status"),
                rs.getString("avatar_url")),
                id.toString()).stream().findFirst().orElseThrow(NotFound::new);
    }

    private void addFilter(StringBuilder where, List<Object> values, String sql, Object value) {
        if (value != null) { where.append(sql); values.add(value); }
    }

    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(UUID.fromString(rs.getString("id")),
                resolveIdentifier(rs.getString("role"), rs.getString("user_code"), rs.getString("email")),
                rs.getString("full_name"), rs.getString("email"), rs.getString("role"),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("major_code"), rs.getString("cohort"), rs.getString("avatar_url"));
    }

    private static String resolveIdentifier(String role, String userCode, String email) {
        if ("ADVISOR".equals(role) && email != null) {
            int at = email.indexOf('@');
            if (at > 0) return email.substring(0, at);
        }
        return userCode;
    }
}
