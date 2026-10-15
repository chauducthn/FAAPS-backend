package vn.edu.fpt.faaps.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAssignmentService {
    private final JdbcTemplate jdbc;

    public AdminAssignmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Advisor> advisors() {
        return jdbc.query("""
                SELECT a.user_id, a.advisor_code, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS active_students
                FROM advisors a JOIN users u ON u.id = a.user_id
                LEFT JOIN student_advisor_assignments sa ON sa.advisor_id = a.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                GROUP BY a.user_id, a.advisor_code, u.full_name, a.max_advisees
                ORDER BY u.full_name
                """, this::mapAdvisor);
    }

    public List<Cohort> cohorts() {
        return jdbc.query("""
                SELECT cv.version AS cohort, COUNT(*) AS students
                FROM students s JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                GROUP BY cv.version ORDER BY cv.version
                """, (result, row) -> new Cohort(result.getString("cohort"), result.getLong("students")));
    }

    public List<Student> students(String cohort, boolean unassignedOnly) {
        if (cohort == null || cohort.isBlank()) {
            throw new IllegalArgumentException("cohort is required");
        }
        return jdbc.query("""
                SELECT s.user_id, s.student_code, u.full_name, cv.version AS cohort,
                       sa.advisor_id, advisor_user.full_name AS advisor_name
                FROM students s JOIN users u ON u.id = s.user_id
                JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                LEFT JOIN student_advisor_assignments sa ON sa.student_id = s.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                LEFT JOIN users advisor_user ON advisor_user.id = sa.advisor_id
                WHERE cv.version = ? AND (? = FALSE OR sa.id IS NULL)
                ORDER BY s.student_code
                """, (result, row) -> new Student(UUID.fromString(result.getString("user_id")),
                result.getString("student_code"), result.getString("full_name"),
                result.getString("cohort"), result.getString("advisor_id"),
                result.getString("advisor_name")), cohort, unassignedOnly);
    }

    public AdvisorDetail advisor(UUID advisorId) {
        Advisor advisor = advisors().stream().filter(item -> item.id().equals(advisorId))
                .findFirst().orElseThrow(Conflict::new);
        List<Student> students = jdbc.query("""
                SELECT s.user_id, s.student_code, u.full_name, cv.version AS cohort,
                       sa.advisor_id, advisor_user.full_name AS advisor_name
                FROM student_advisor_assignments sa JOIN students s ON s.user_id = sa.student_id
                JOIN users u ON u.id = s.user_id
                JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                JOIN users advisor_user ON advisor_user.id = sa.advisor_id
                WHERE sa.advisor_id = ? AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                ORDER BY cv.version, s.student_code
                """, (result, row) -> new Student(UUID.fromString(result.getString("user_id")),
                result.getString("student_code"), result.getString("full_name"),
                result.getString("cohort"), result.getString("advisor_id"),
                result.getString("advisor_name")), advisorId.toString());
        return new AdvisorDetail(advisor, students);
    }

    public List<History> history(UUID studentId) {
        return jdbc.query("""
                SELECT sa.id, sa.advisor_id, u.full_name AS advisor_name, sa.assigned_date,
                       sa.end_date, sa.status
                FROM student_advisor_assignments sa JOIN users u ON u.id = sa.advisor_id
                WHERE sa.student_id = ? ORDER BY sa.assigned_date DESC, sa.created_at DESC
                """, (result, row) -> new History(result.getString("id"),
                result.getString("advisor_id"), result.getString("advisor_name"),
                result.getDate("assigned_date").toLocalDate(),
                result.getDate("end_date") == null ? null : result.getDate("end_date").toLocalDate(),
                result.getString("status")), studentId.toString());
    }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId) {
        validateIds(studentIds);
        lockAdvisor(advisorId);
        Advisor advisor = requireAdvisor(advisorId);
        long load = advisor.activeStudents();
        int changed = 0;
        for (UUID studentId : studentIds.stream().sorted().toList()) {
            lockStudent(studentId);
            String activeAdvisor = activeAdvisor(studentId);
            if (advisorId.toString().equals(activeAdvisor)) {
                continue;
            }
            if (advisor.maxAdvisees() != null && load >= advisor.maxAdvisees()) {
                throw new Conflict();
            }
            if (activeAdvisor != null) {
                jdbc.update("""
                        UPDATE student_advisor_assignments SET status = 'TRANSFERRED',
                            end_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP
                        WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                        """, studentId.toString());
                jdbc.update("""
                        UPDATE advising_requests SET advisor_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE student_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                        """, advisorId.toString(), studentId.toString());
                int cancelled = jdbc.update("""
                        UPDATE advising_sessions SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
                        WHERE student_id = ? AND advisor_id = ?
                          AND status = 'SCHEDULED' AND scheduled_at > CURRENT_TIMESTAMP
                        """, studentId.toString(), activeAdvisor);
                if (cancelled > 0) {
                    jdbc.update("""
                            INSERT INTO notifications
                                (id, user_id, notification_type, title, message, is_read)
                            VALUES (?, ?, 'INFO', 'Appointment cancelled',
                                'Your future appointment was cancelled after advisor reassignment. Please book a new slot.', FALSE)
                            """, UUID.randomUUID().toString(), studentId.toString());
                }
            }
            insertAssignment(studentId, advisorId);
            changed++;
            load++;
        }
        return changed;
    }

    public Preview preview(String cohort, List<UUID> advisorIds) {
        List<Advisor> selected = selectedAdvisors(advisorIds);
        List<Student> unassigned = students(cohort, true);
        return new Preview(cohort, unassigned.size(), distribute(selected, unassigned.size()));
    }

    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds) {
        for (UUID advisorId : advisorIds.stream().sorted().toList()) {
            lockAdvisor(advisorId);
        }
        List<Advisor> selected = selectedAdvisors(advisorIds);
        List<Student> candidates = students(cohort, true);
        List<UUID> studentIds = candidates.stream().map(Student::id).sorted().toList();
        for (UUID studentId : studentIds) {
            lockStudent(studentId);
        }
        List<Student> unassigned = students(cohort, true);
        Map<UUID, Integer> assignedCounts = new HashMap<>();
        for (Advisor advisor : selected) {
            assignedCounts.put(advisor.id(), 0);
        }
        Map<UUID, Long> loads = new HashMap<>();
        for (Advisor advisor : selected) {
            loads.put(advisor.id(), advisor.activeStudents());
        }
        for (Student student : unassigned) {
            Advisor next = nextAdvisor(selected, loads);
            insertAssignment(student.id(), next.id());
            loads.put(next.id(), loads.get(next.id()) + 1);
            assignedCounts.put(next.id(), assignedCounts.get(next.id()) + 1);
        }
        return new Result(unassigned.size(), assignedCounts);
    }

    private Map<UUID, Integer> distribute(List<Advisor> advisors, int count) {
        Map<UUID, Integer> assigned = new HashMap<>();
        Map<UUID, Long> loads = new HashMap<>();
        for (Advisor advisor : advisors) {
            assigned.put(advisor.id(), 0);
            loads.put(advisor.id(), advisor.activeStudents());
        }
        for (int index = 0; index < count; index++) {
            Advisor next = nextAdvisor(advisors, loads);
            assigned.put(next.id(), assigned.get(next.id()) + 1);
            loads.put(next.id(), loads.get(next.id()) + 1);
        }
        return assigned;
    }

    private Advisor nextAdvisor(List<Advisor> advisors, Map<UUID, Long> loads) {
        return advisors.stream()
                .filter(advisor -> advisor.maxAdvisees() == null || loads.get(advisor.id()) < advisor.maxAdvisees())
                .min(Comparator.comparingLong((Advisor advisor) -> loads.get(advisor.id()))
                        .thenComparing(advisor -> advisor.id().toString()))
                .orElseThrow(Conflict::new);
    }

    private List<Advisor> selectedAdvisors(List<UUID> ids) {
        validateIds(ids);
        List<Advisor> available = advisors();
        List<Advisor> selected = new ArrayList<>();
        for (UUID id : ids) {
            selected.add(available.stream().filter(advisor -> advisor.id().equals(id))
                    .findFirst().orElseThrow(Conflict::new));
        }
        return selected;
    }

    private void validateIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("ids must be unique and contain 1-100 values");
        }
    }

    private Advisor requireAdvisor(UUID id) {
        return advisors().stream().filter(advisor -> advisor.id().equals(id))
                .findFirst().orElseThrow(Conflict::new);
    }

    private void lockAdvisor(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM advisors WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    private void lockStudent(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM students WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    private String activeAdvisor(UUID studentId) {
        List<String> rows = jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, (result, row) -> result.getString(1), studentId.toString());
        if (rows.size() > 1) {
            throw new Conflict();
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertAssignment(UUID studentId, UUID advisorId) {
        jdbc.update("""
                INSERT INTO student_advisor_assignments
                    (id, student_id, advisor_id, assigned_date, is_primary, status)
                VALUES (?, ?, ?, CURRENT_DATE, TRUE, 'ACTIVE')
                """, UUID.randomUUID().toString(), studentId.toString(), advisorId.toString());
        jdbc.update("""
                UPDATE advising_requests SET advisor_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND advisor_id IS NULL
                  AND status IN ('PENDING', 'IN_PROGRESS')
                """, advisorId.toString(), studentId.toString());
    }

    private Advisor mapAdvisor(ResultSet result, int row) throws SQLException {
        return new Advisor(UUID.fromString(result.getString("user_id")), result.getString("advisor_code"),
                result.getString("full_name"), (Integer) result.getObject("max_advisees"),
                result.getLong("active_students"));
    }

    public record Advisor(UUID id, String advisorCode, String fullName,
            Integer maxAdvisees, long activeStudents) {
    }

    public record Cohort(String cohort, long students) {
    }

    public record Student(UUID id, String studentCode, String fullName,
            String cohort, String advisorId, String advisorName) {
    }

    public record AdvisorDetail(Advisor advisor, List<Student> students) {
    }

    public record History(String id, String advisorId, String advisorName,
            LocalDate assignedDate, LocalDate endDate, String status) {
    }

    public record Preview(String cohort, int unassignedStudents, Map<UUID, Integer> allocation) {
    }

    public record Result(int assigned, Map<UUID, Integer> allocation) {
    }

    public static class Conflict extends RuntimeException {
    }
}
