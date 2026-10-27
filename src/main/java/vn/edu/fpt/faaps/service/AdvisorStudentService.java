package vn.edu.fpt.faaps.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdvisorStudentService {
    private final JdbcTemplate jdbc;
    public AdvisorStudentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Page list(UUID advisorId, String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        StringBuilder where = new StringBuilder("""
                 WHERE saa.advisor_id=? AND saa.status='ACTIVE' AND saa.end_date IS NULL
                   AND u.status='ACTIVE' AND r.code='STUDENT'
                """);
        List<Object> args = new ArrayList<>(List.of(advisorId.toString()));
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(s.student_code) LIKE ?)");
            args.addAll(List.of(term, term, term));
        }
        String from = """
                 FROM student_advisor_assignments saa JOIN students s ON s.user_id=saa.student_id
                 JOIN users u ON u.id=s.user_id JOIN roles r ON r.id=u.role_id
                 JOIN majors m ON m.id=s.major_id JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id
                 LEFT JOIN semesters sem ON sem.id=s.enrollment_semester_id
                """;
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        List<Object> paging = new ArrayList<>(args); paging.add(size); paging.add(page * size);
        List<Student> items = jdbc.query("""
                SELECT s.user_id,s.student_code,u.full_name,u.email,m.code major_code,m.name major_name,
                       cv.version curriculum_version,s.current_semester,s.current_gpa,s.total_credits_earned,
                       s.academic_status,sem.code enrollment_semester_code,saa.assigned_date,saa.group_name,saa.is_primary
                """ + from + where + " ORDER BY u.full_name,s.user_id LIMIT ? OFFSET ?", (rs, row) -> new Student(
                UUID.fromString(rs.getString("user_id")), rs.getString("student_code"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("major_code"), rs.getString("major_name"),
                rs.getString("curriculum_version"), rs.getInt("current_semester"),
                rs.getBigDecimal("current_gpa"), rs.getInt("total_credits_earned"),
                rs.getString("academic_status"), rs.getString("enrollment_semester_code"),
                date(rs, "assigned_date"), rs.getString("group_name"), rs.getBoolean("is_primary")), paging.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public boolean owns(UUID advisorId, UUID studentId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE advisor_id=? AND student_id=? AND status='ACTIVE' AND end_date IS NULL
                """, Integer.class, advisorId.toString(), studentId.toString());
        return count != null && count > 0;
    }

    private LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getDate(column); return value == null ? null : value.toLocalDate();
    }
    public record Student(UUID id, String studentCode, String fullName, String email, String majorCode,
            String majorName, String curriculumVersion, int currentSemester, java.math.BigDecimal currentGpa,
            int totalCreditsEarned, String academicStatus, String enrollmentSemesterCode,
            LocalDate assignedDate, String groupName, boolean isPrimary) {}
    public record Page(List<Student> items, int page, int size, long totalElements, long totalPages) {}
}

