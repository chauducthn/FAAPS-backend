package vn.edu.fpt.faaps.advising.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.Profile;

@Repository
public class AdvisorProfileRepository {
    private final JdbcTemplate jdbc;
    public AdvisorProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Profile find(UUID advisorId) {
        return jdbc.query("""
                SELECT u.id, a.advisor_code, u.full_name, u.email, u.phone, a.specialization,
                       a.max_advisees, a.status, a.assignment_start_date,
                       COUNT(saa.id) active_advisees
                FROM users u JOIN advisors a ON a.user_id=u.id
                LEFT JOIN student_advisor_assignments saa ON saa.advisor_id=a.user_id
                     AND saa.status='ACTIVE' AND saa.end_date IS NULL
                WHERE u.id=?
                GROUP BY u.id, a.advisor_code, u.full_name, u.email, u.phone, a.specialization,
                         a.max_advisees, a.status, a.assignment_start_date
                """, (rs, row) -> new Profile(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("advisor_code"),
                    rs.getString("full_name"),
                    rs.getString("email"),
                    rs.getString("phone"),
                    rs.getString("specialization"),
                    rs.getInt("max_advisees"),
                    rs.getInt("active_advisees"),
                    rs.getString("status"),
                    rs.getDate("assignment_start_date") == null ? null : rs.getDate("assignment_start_date").toLocalDate()
                ), advisorId.toString())
                .stream().findFirst().orElse(null);
    }

    public int update(UUID advisorId, String fullName, String phone, String specialization) {
        int userUpdated = jdbc.update("""
                UPDATE users SET full_name=?, phone=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND EXISTS (SELECT 1 FROM advisors WHERE user_id=?)
                """, fullName, phone, advisorId.toString(), advisorId.toString());

        if (userUpdated > 0) {
            jdbc.update("""
                    UPDATE advisors SET specialization=?, updated_at=CURRENT_TIMESTAMP
                    WHERE user_id=?
                    """, specialization, advisorId.toString());
        }
        return userUpdated;
    }
}
