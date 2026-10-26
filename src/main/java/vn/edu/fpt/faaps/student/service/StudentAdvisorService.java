package vn.edu.fpt.faaps.student.service;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StudentAdvisorService {
    private final JdbcTemplate jdbc;
    public StudentAdvisorService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Advisor advisor(UUID studentId) {
        return jdbc.query("""
                SELECT u.id,u.full_name,u.email,u.phone,a.max_advisees,a.status,a.assignment_start_date,
                       saa.assigned_date,saa.group_name,saa.is_primary
                FROM student_advisor_assignments saa
                JOIN advisors a ON a.user_id=saa.advisor_id JOIN users u ON u.id=a.user_id
                JOIN roles r ON r.id=u.role_id
                WHERE saa.student_id=? AND saa.status='ACTIVE' AND saa.end_date IS NULL
                  AND a.status='ACTIVE' AND u.status='ACTIVE' AND r.code='ADVISOR'
                ORDER BY saa.is_primary DESC,saa.assigned_at DESC LIMIT 1
                """, (rs, row) -> new Advisor(UUID.fromString(rs.getString("id")), rs.getString("email"),
                rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                rs.getInt("max_advisees"), rs.getString("status"), date(rs, "assignment_start_date"),
                date(rs, "assigned_date"), rs.getString("group_name"), rs.getBoolean("is_primary")),
                studentId.toString()).stream().findFirst().orElse(null);
    }

    private LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    public record Advisor(UUID id, String advisorCode, String fullName, String email, String phone,
            int maxAdvisees, String status, LocalDate assignmentStartDate, LocalDate assignedDate,
            String groupName, boolean isPrimary) {}
}
