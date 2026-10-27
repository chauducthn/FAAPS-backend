package vn.edu.fpt.faaps.advising.repository;

import vn.edu.fpt.faaps.advising.service.AdminAssignmentService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Advisor;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.AdvisorDetail;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Cohort;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.History;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.NotFound;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Page;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Student;

public final class AssignmentReadRepository {
    private final JdbcTemplate jdbc;
    public AssignmentReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Advisor> advisors() {
        return jdbc.query("""
                SELECT a.user_id, u.email, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS active_students
                FROM advisors a JOIN users u ON u.id = a.user_id
                JOIN roles r ON r.id=u.role_id AND r.code='ADVISOR'
                LEFT JOIN student_advisor_assignments sa ON sa.advisor_id = a.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                GROUP BY a.user_id, u.email, u.full_name, a.max_advisees
                ORDER BY u.full_name
                """, this::mapAdvisor);
    }

    public Page<Advisor> advisors(int page, int size, String search, String status) {
        validatePage(page, size);
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String state = status == null || status.isBlank() ? null : status.toUpperCase();
        if (state != null && !List.of("ACTIVE", "INACTIVE", "ON_LEAVE").contains(state)) {
            throw new IllegalArgumentException("status must be ACTIVE, INACTIVE, or ON_LEAVE");
        }
        String where = " WHERE (? IS NULL OR a.status = ?) AND (? IS NULL OR LOWER(u.email) LIKE ? OR LOWER(u.full_name) LIKE ?)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM advisors a JOIN users u ON u.id = a.user_id JOIN roles r ON r.id=u.role_id AND r.code='ADVISOR'" + where,
                Long.class, state, state, term, term, term);
        List<Advisor> items = jdbc.query("""
                SELECT a.user_id, u.email, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS active_students
                FROM advisors a JOIN users u ON u.id = a.user_id
                JOIN roles r ON r.id=u.role_id AND r.code='ADVISOR'
                LEFT JOIN student_advisor_assignments sa ON sa.advisor_id = a.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                """ + where + " GROUP BY a.user_id, u.email, u.full_name, a.max_advisees "
                + "ORDER BY u.full_name LIMIT ? OFFSET ?", this::mapAdvisor,
                state, state, term, term, term, size, page * size);
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    public List<Cohort> cohorts() {
        return jdbc.query("""
                SELECT cv.id AS cohort_id, cv.version AS cohort, m.code AS major_code, COUNT(s.user_id) AS students
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                LEFT JOIN students s ON s.curriculum_version_id = cv.id
                GROUP BY cv.id, cv.version, m.code ORDER BY m.code, cv.version
                """, (result, row) -> new Cohort(UUID.fromString(result.getString("cohort_id")),
                result.getString("cohort"), result.getString("major_code"), result.getLong("students")));
    }

    public List<Student> students(String cohort, boolean unassignedOnly) {
        if (cohort == null || cohort.isBlank()) {
            throw new IllegalArgumentException("cohort is required");
        }
        return jdbc.query("""
                SELECT s.user_id, s.student_code, u.full_name, cv.id AS cohort_id,
                       cv.version AS cohort, m.code AS major_code, sa.advisor_id,
                       advisor_user.full_name AS advisor_name, sa.assigned_date
                FROM students s JOIN users u ON u.id = s.user_id
                JOIN roles r ON r.id=u.role_id AND r.code='STUDENT'
                JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                JOIN majors m ON m.id = cv.major_id
                LEFT JOIN student_advisor_assignments sa ON sa.student_id = s.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                LEFT JOIN users advisor_user ON advisor_user.id = sa.advisor_id
                WHERE cv.version = ? AND (? = FALSE OR sa.id IS NULL)
                ORDER BY s.student_code
                """, this::mapStudent, cohort, unassignedOnly);
    }

    public Page<Student> students(UUID cohortId, String cohort, boolean unassignedOnly,
            int page, int size, String search, String advisorId, String status) {
        validatePage(page, size);
        if (cohortId == null && (cohort == null || cohort.isBlank()))
            throw new IllegalArgumentException("cohortId is required");
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String cohortFilter = cohortId == null ? "cv.version = ?" : "cv.id = ?";
        Object cohortValue = cohortId == null ? cohort : cohortId.toString();
        StringBuilder where = new StringBuilder(" WHERE " + cohortFilter + " AND (? = FALSE OR sa.id IS NULL) AND (? IS NULL OR sa.advisor_id = ?) AND (? IS NULL OR u.status = ?) AND (? IS NULL OR LOWER(s.student_code) LIKE ? OR LOWER(u.full_name) LIKE ?)");
        Object[] params = {cohortValue, unassignedOnly, advisorId, advisorId, status, status, term, term, term};
        String from = " FROM students s JOIN users u ON u.id=s.user_id JOIN roles r ON r.id=u.role_id AND r.code='STUDENT' JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id JOIN majors m ON m.id=cv.major_id LEFT JOIN student_advisor_assignments sa ON sa.student_id=s.user_id AND sa.status='ACTIVE' AND sa.end_date IS NULL LEFT JOIN users advisor_user ON advisor_user.id=sa.advisor_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, params);
        List<Student> items = jdbc.query("SELECT s.user_id,s.student_code,u.full_name,cv.id AS cohort_id,cv.version AS cohort,m.code AS major_code,sa.advisor_id,advisor_user.full_name AS advisor_name,sa.assigned_date" + from + where + " ORDER BY s.student_code LIMIT ? OFFSET ?",
                this::mapStudent, append(params, size, page * size));
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    public AdvisorDetail advisor(UUID advisorId) {
        Advisor advisor = advisors().stream().filter(item -> item.id().equals(advisorId))
                .findFirst().orElseThrow(() -> new NotFound("Advisor not found or inactive"));
        List<Student> students = jdbc.query("""
                SELECT s.user_id, s.student_code, u.full_name, cv.id AS cohort_id,
                       cv.version AS cohort, m.code AS major_code, sa.advisor_id,
                       advisor_user.full_name AS advisor_name, sa.assigned_date
                FROM student_advisor_assignments sa JOIN students s ON s.user_id = sa.student_id
                JOIN users u ON u.id = s.user_id
                JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                JOIN majors m ON m.id = cv.major_id
                JOIN users advisor_user ON advisor_user.id = sa.advisor_id
                WHERE sa.advisor_id = ? AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                ORDER BY cv.version, s.student_code
                """, this::mapStudent, advisorId.toString());
        Map<String, Object> profile = jdbc.queryForMap("SELECT u.email,a.status FROM advisors a JOIN users u ON u.id=a.user_id WHERE a.user_id=?", advisorId.toString());
        List<Cohort> allCohorts = jdbc.query("SELECT cv.id AS cohort_id,cv.version AS cohort,m.code AS major_code,COUNT(s.user_id) AS students FROM curriculum_versions cv JOIN majors m ON m.id=cv.major_id LEFT JOIN students s ON s.curriculum_version_id=cv.id GROUP BY cv.id,cv.version,m.code ORDER BY m.code,cv.version",
                (rs, row) -> new Cohort(UUID.fromString(rs.getString("cohort_id")), rs.getString("cohort"), rs.getString("major_code"), rs.getLong("students")));
        List<Cohort> configured = jdbc.query("SELECT cv.id AS cohort_id,cv.version AS cohort,m.code AS major_code,COUNT(s.user_id) AS students FROM cohort_advisors ca JOIN curriculum_versions cv ON cv.id=ca.cohort_id JOIN majors m ON m.id=cv.major_id LEFT JOIN students s ON s.curriculum_version_id=cv.id WHERE ca.advisor_id=? AND ca.is_active=TRUE GROUP BY cv.id,cv.version,m.code ORDER BY m.code,cv.version",
                (rs, row) -> new Cohort(UUID.fromString(rs.getString("cohort_id")), rs.getString("cohort"), rs.getString("major_code"), rs.getLong("students")), advisorId.toString());
        return new AdvisorDetail(advisor, (String) profile.get("email"),
                (String) profile.get("status"), students, allCohorts, configured);
    }

    public List<History> history(UUID studentId) {
        return jdbc.query("""
                SELECT sa.id, sa.advisor_id, u.email AS advisor_email, u.full_name AS advisor_name,
                       sa.assigned_date, sa.assigned_at, sa.assigned_by_user_id,
                       actor.full_name AS actor_name, sa.notes, sa.end_date, sa.status
                FROM student_advisor_assignments sa JOIN users u ON u.id = sa.advisor_id
                LEFT JOIN users actor ON actor.id=sa.assigned_by_user_id
                WHERE sa.student_id = ? ORDER BY sa.assigned_at DESC, sa.id DESC
                """, (result, row) -> new History(result.getString("id"),
                result.getString("advisor_id"), result.getString("advisor_email"), result.getString("advisor_name"),
                result.getDate("assigned_date").toLocalDate(), result.getTimestamp("assigned_at") == null
                        ? null : result.getTimestamp("assigned_at").toLocalDateTime(),
                result.getString("assigned_by_user_id"), result.getString("actor_name"), result.getString("notes"),
                result.getDate("end_date") == null ? null : result.getDate("end_date").toLocalDate(),
                result.getString("status")), studentId.toString());
    }

    private Advisor mapAdvisor(ResultSet result, int row) throws SQLException {
        return new Advisor(UUID.fromString(result.getString("user_id")), result.getString("email"),
                result.getString("full_name"), (Integer) result.getObject("max_advisees"),
                result.getLong("active_students"));
    }

    private Student mapStudent(ResultSet result, int row) throws SQLException {
        return new Student(UUID.fromString(result.getString("user_id")), result.getString("student_code"),
                result.getString("full_name"), UUID.fromString(result.getString("cohort_id")),
                result.getString("cohort"), result.getString("major_code"), result.getString("advisor_id"),
                result.getString("advisor_name"), result.getDate("assigned_date") == null ? null
                        : result.getDate("assigned_date").toLocalDate());
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("page or size is invalid");
    }

    private Object[] append(Object[] values, Object... extra) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + extra.length);
        System.arraycopy(extra, 0, result, values.length, extra.length);
        return result;
    }

}


