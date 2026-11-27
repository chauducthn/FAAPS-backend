package vn.edu.fpt.faaps.advising.repository;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.Profile;

@Repository
public class AdvisorProfileRepository {
    private static final Logger log = LoggerFactory.getLogger(AdvisorProfileRepository.class);
    private final JdbcTemplate jdbc;
    public AdvisorProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Profile find(UUID advisorId) {
        try {
            return jdbc.query("""
                    SELECT u.id, a.advisor_code, u.full_name, u.email, u.phone, u.date_of_birth,
                           u.status AS account_status, a.major_group_id, mg.code AS major_group_code,
                           mg.name AS major_group_name, a.specialization,
                           a.max_advisees, a.status, a.assignment_start_date,
                           COUNT(saa.id) active_advisees
                    FROM users u JOIN advisors a ON a.user_id=u.id
                    LEFT JOIN major_groups mg ON mg.id=a.major_group_id
                    LEFT JOIN student_advisor_assignments saa ON saa.advisor_id=a.user_id
                         AND saa.status='ACTIVE' AND saa.end_date IS NULL
                    WHERE u.id=?
                    GROUP BY u.id, a.advisor_code, u.full_name, u.email, u.phone, u.date_of_birth,
                             u.status, a.major_group_id, mg.code, mg.name, a.specialization,
                             a.max_advisees, a.status, a.assignment_start_date
                    """, (rs, row) -> new Profile(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("advisor_code"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                        rs.getString("account_status"),
                        rs.getString("major_group_id"),
                        rs.getString("major_group_code"),
                        rs.getString("major_group_name"),
                        rs.getString("specialization"),
                        rs.getInt("max_advisees"),
                        rs.getInt("active_advisees"),
                        rs.getString("status"),
                        rs.getDate("assignment_start_date") == null ? null : rs.getDate("assignment_start_date").toLocalDate()
                    ), advisorId.toString())
                    .stream().findFirst().orElse(null);
        } catch (BadSqlGrammarException ex) {
            log.debug("Falling back to advisor profile query without major groups: {}", ex.getMessage());
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
    }

    public boolean emailInUse(String normalizedEmail, UUID excludeUserId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = ? AND id <> ?",
                Integer.class, normalizedEmail, excludeUserId.toString());
        return count != null && count > 0;
    }

    public boolean majorGroupExists(UUID majorGroupId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM major_groups WHERE id = ?",
                Integer.class, majorGroupId.toString());
        return count != null && count > 0;
    }

    public int update(UUID advisorId, String fullName, String phone, String specialization) {
        return update(advisorId, fullName, phone, specialization, null, null);
    }

    public int update(UUID advisorId, String fullName, String phone, String specialization,
            String email, UUID majorGroupId) {
        int userUpdated;
        if (email != null) {
            userUpdated = jdbc.update("""
                    UPDATE users SET full_name=?, phone=?, email=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND EXISTS (SELECT 1 FROM advisors WHERE user_id=?)
                    """, fullName, phone, email, advisorId.toString(), advisorId.toString());
        } else {
            userUpdated = jdbc.update("""
                    UPDATE users SET full_name=?, phone=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND EXISTS (SELECT 1 FROM advisors WHERE user_id=?)
                    """, fullName, phone, advisorId.toString(), advisorId.toString());
        }

        if (userUpdated > 0) {
            if (majorGroupId != null) {
                jdbc.update("""
                        UPDATE advisors SET specialization=?, major_group_id=?, updated_at=CURRENT_TIMESTAMP
                        WHERE user_id=?
                        """, specialization, majorGroupId.toString(), advisorId.toString());
            } else {
                jdbc.update("""
                        UPDATE advisors SET specialization=?, updated_at=CURRENT_TIMESTAMP
                        WHERE user_id=?
                        """, specialization, advisorId.toString());
            }
        }
        return userUpdated;
    }

    /** Writes only the advisor-specific column, leaving the shared identity columns untouched. */
    public int updateSpecialization(UUID advisorId, String specialization) {
        return jdbc.update("""
                UPDATE advisors SET specialization=?, updated_at=CURRENT_TIMESTAMP
                WHERE user_id=?
                """, specialization, advisorId.toString());
    }
}
