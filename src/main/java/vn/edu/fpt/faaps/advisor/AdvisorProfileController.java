package vn.edu.fpt.faaps.advisor;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/advisor/profile")
public class AdvisorProfileController {
    private final JdbcTemplate jdbc;
    public AdvisorProfileController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Profile profile(@AuthenticationPrincipal AuthSession advisor) {
        return jdbc.query("""
                SELECT u.id,u.full_name,u.email,u.phone,a.max_advisees,a.status,a.assignment_start_date,
                       COUNT(saa.id) active_advisees
                FROM users u JOIN advisors a ON a.user_id=u.id
                LEFT JOIN student_advisor_assignments saa ON saa.advisor_id=a.user_id
                     AND saa.status='ACTIVE' AND saa.end_date IS NULL
                WHERE u.id=? GROUP BY u.id,u.full_name,u.email,u.phone,a.max_advisees,a.status,a.assignment_start_date
                """, (rs, row) -> new Profile(UUID.fromString(rs.getString("id")), rs.getString("full_name"),
                rs.getString("email"), rs.getString("phone"), rs.getInt("max_advisees"),
                rs.getInt("active_advisees"), rs.getString("status"),
                rs.getDate("assignment_start_date") == null ? null : rs.getDate("assignment_start_date").toLocalDate()),
                advisor.id().toString()).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Advisor profile not found"));
    }

    public record Profile(UUID id, String fullName, String email, String phone, int maxAdvisees,
            int activeAdvisees, String status, LocalDate assignmentStartDate) {}
}
