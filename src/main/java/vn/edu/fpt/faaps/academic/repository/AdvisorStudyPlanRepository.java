package vn.edu.fpt.faaps.academic.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Page;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Summary;

@Repository
public class AdvisorStudyPlanRepository {
    private static final String ACCESS = """
            FROM study_plans p JOIN students s ON s.user_id=p.student_id
            JOIN users u ON u.id=s.user_id JOIN semesters sem ON sem.id=p.semester_id
            JOIN student_advisor_assignments a ON a.student_id=s.user_id
            WHERE a.advisor_id=? AND a.status='ACTIVE' AND a.end_date IS NULL
            """;
    private final JdbcTemplate jdbc;

    public AdvisorStudyPlanRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Page list(UUID advisorId, String status, int page, int size) {
        validatePage(page, size);
        String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        String filter = normalized == null ? "" : " AND p.status=?";
        List<Object> args = new ArrayList<>(List.of(advisorId.toString()));
        if (normalized != null) args.add(normalized);
        long total = jdbc.queryForObject("SELECT COUNT(DISTINCT p.id) " + ACCESS + filter,
                Long.class, args.toArray());
        args.add(size); args.add(page * size);
        List<Summary> items = jdbc.query("""
                SELECT DISTINCT p.id,p.student_id,s.student_code,u.full_name,sem.code semester_code,
                       p.version,p.total_credits_planned,p.status,p.submitted_at
                """ + ACCESS + filter + " ORDER BY p.submitted_at DESC,p.id LIMIT ? OFFSET ?",
                (rs, row) -> new Summary(rs.getString("id"), rs.getString("student_id"),
                        rs.getString("student_code"), rs.getString("full_name"),
                        rs.getString("semester_code"), rs.getInt("version"),
                        rs.getInt("total_credits_planned"), rs.getString("status"),
                        time(rs.getTimestamp("submitted_at"))), args.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Student student(UUID advisorId, UUID planId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT p.student_id,s.student_code,u.full_name FROM study_plans p
                JOIN students s ON s.user_id=p.student_id JOIN users u ON u.id=s.user_id
                JOIN student_advisor_assignments a ON a.student_id=s.user_id
                WHERE p.id=? AND a.advisor_id=? AND a.status='ACTIVE' AND a.end_date IS NULL
                """ + suffix, (rs, row) -> new Student(UUID.fromString(rs.getString("student_id")),
                rs.getString("student_code"), rs.getString("full_name")), planId.toString(), advisorId.toString())
                .stream().findFirst().orElse(null);
    }

    public int decide(UUID planId, UUID advisorId, String decision, String comment) {
        return jdbc.update("""
                UPDATE study_plans SET status=?,reviewed_by_user_id=?,reviewed_at=CURRENT_TIMESTAMP,
                    review_notes=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SUBMITTED' AND is_current=TRUE
                """, decision, advisorId.toString(), comment, planId.toString());
    }

    public void notifyStudent(UUID studentId, UUID planId, String decision) {
        jdbc.update("""
                INSERT INTO notifications(id,recipient_user_id,notification_type,title,message,action_url,is_read)
                VALUES (?,?,'PLAN_STATUS','Study plan reviewed',?, '/student/plans',FALSE)
                """, UUID.randomUUID().toString(), studentId.toString(),
                "Your study plan was " + decision.toLowerCase().replace('_', ' ') + ".");
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
    }
    private LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    public record Student(UUID id, String code, String name) {}
}
