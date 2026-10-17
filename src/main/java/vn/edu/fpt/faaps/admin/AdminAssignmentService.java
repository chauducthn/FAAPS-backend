package vn.edu.fpt.faaps.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final CohortAdvisorService cohorts;

    public AdminAssignmentService(JdbcTemplate jdbc, CohortAdvisorService cohorts) {
        this.jdbc = jdbc;
        this.cohorts = cohorts;
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
        return assign(studentIds, advisorId, false);
    }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation) {
        validateIds(studentIds);
        lockAdvisor(advisorId);
        Advisor advisor = requireAdvisor(advisorId);
        long load = advisor.activeStudents();
        int changed = 0;
        List<UUID> sortedStudents = studentIds.stream().sorted().toList();
        for (UUID studentId : sortedStudents) {
            lockStudent(studentId);
        }
        List<AppointmentImpact> impacts = appointmentImpacts(sortedStudents, advisorId);
        if (!impacts.isEmpty() && !acknowledgeAppointmentCancellation) {
            throw new AppointmentWarningRequired(impacts);
        }
        for (UUID studentId : sortedStudents) {
            String activeAdvisor = activeAdvisor(studentId);
            if (advisorId.toString().equals(activeAdvisor)) {
                continue;
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

    public List<AppointmentImpact> appointmentImpacts(List<UUID> studentIds, UUID newAdvisorId) {
        validateIds(studentIds);
        List<AppointmentImpact> impacts = new ArrayList<>();
        for (UUID studentId : studentIds) {
            String currentAdvisorId = activeAdvisor(studentId);
            if (currentAdvisorId == null || currentAdvisorId.equals(newAdvisorId.toString())) continue;
            impacts.addAll(jdbc.query("""
                    SELECT id, student_id, advisor_id, scheduled_at FROM advising_sessions
                    WHERE student_id = ? AND advisor_id = ? AND status = 'SCHEDULED'
                      AND scheduled_at > CURRENT_TIMESTAMP ORDER BY scheduled_at
                    """, (result, row) -> new AppointmentImpact(
                    UUID.fromString(result.getString("id")), UUID.fromString(result.getString("student_id")),
                    UUID.fromString(result.getString("advisor_id")),
                    result.getTimestamp("scheduled_at").toLocalDateTime()),
                    studentId.toString(), currentAdvisorId));
        }
        return impacts;
    }

    public Preview preview(String cohort, List<UUID> advisorIds) {
        List<Advisor> selected = selectedAdvisors(advisorIds);
        List<Student> unassigned = students(cohort, true);
        return new Preview(cohort, unassigned.size(), distribute(selected, unassigned.size()));
    }

    public Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds) {
        List<Advisor> selected = selectedAdvisorsForCohort(cohortId, advisorIds);
        int count = unassignedIds(cohortId).size();
        return allocationResponse(cohortId, count, selected, distribute(selected, count));
    }

    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds) {
        cohorts.lockCohort(cohortId);
        List<Advisor> initial = selectedAdvisorsForCohort(cohortId, advisorIds);
        for (UUID advisorId : initial.stream().map(Advisor::id).sorted().toList()) lockAdvisor(advisorId);
        List<Advisor> selected = selectedAdvisorsForCohort(cohortId, advisorIds);
        for (UUID studentId : unassignedIds(cohortId)) lockStudent(studentId);
        List<UUID> students = unassignedIds(cohortId);
        Map<UUID, Integer> allocation = new HashMap<>();
        Map<UUID, Long> loads = new HashMap<>();
        for (Advisor advisor : selected) {
            allocation.put(advisor.id(), 0);
            loads.put(advisor.id(), advisor.activeStudents());
        }
        for (UUID studentId : students) {
            Advisor advisor = nextAdvisor(selected, loads);
            insertAssignment(studentId, advisor.id());
            loads.put(advisor.id(), loads.get(advisor.id()) + 1);
            allocation.put(advisor.id(), allocation.get(advisor.id()) + 1);
        }
        return allocationResponse(cohortId, students.size(), selected, allocation);
    }

    private List<UUID> unassignedIds(UUID cohortId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_versions WHERE id = ?",
                Integer.class, cohortId.toString()) == 0) throw new Conflict();
        return jdbc.query("""
                SELECT s.user_id FROM students s
                WHERE s.curriculum_version_id = ?
                  AND NOT EXISTS(SELECT 1 FROM student_advisor_assignments sa
                      WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL)
                ORDER BY s.user_id
                """, (result, row) -> UUID.fromString(result.getString(1)), cohortId.toString());
    }

    private Map<String, Object> allocationResponse(UUID cohortId, int count,
            List<Advisor> advisors, Map<UUID, Integer> allocation) {
        List<Map<String, Object>> limits = advisors.stream().map(advisor -> {
            long after = advisor.activeStudents() + allocation.get(advisor.id());
            int excess = advisor.maxAdvisees() == null ? 0 :
                    (int) Math.max(0, after - advisor.maxAdvisees());
            return Map.<String, Object>of("advisorId", advisor.id(), "assigned", allocation.get(advisor.id()),
                    "totalAfter", after, "overLimitBy", excess, "warning", excess > 0);
        }).toList();
        return Map.of("cohortId", cohortId, "assigned", count, "allocation", limits,
                "warning", limits.stream().anyMatch(item -> (boolean) item.get("warning")));
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

    private List<Advisor> selectedAdvisorsForCohort(UUID cohortId, List<UUID> ids) {
        List<UUID> pool = cohorts.activeIds(cohortId);
        List<UUID> selected = ids == null || ids.isEmpty() ? pool : ids;
        if (selected.isEmpty() || !pool.containsAll(selected)) throw new Conflict();
        return selectedAdvisors(selected);
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

    void lockAdvisor(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM advisors WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    void lockStudent(UUID id) {
        List<String> rows = jdbc.query("SELECT user_id FROM students WHERE user_id = ? FOR UPDATE",
                (result, row) -> result.getString(1), id.toString());
        if (rows.isEmpty()) {
            throw new Conflict();
        }
    }

    String activeAdvisor(UUID studentId) {
        List<String> rows = jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, (result, row) -> result.getString(1), studentId.toString());
        if (rows.size() > 1) {
            throw new Conflict();
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    void insertAssignment(UUID studentId, UUID advisorId) {
        jdbc.update("""
                INSERT INTO student_advisor_assignments
                    (id, student_id, advisor_id, assigned_date, is_primary, status)
                VALUES (?, ?, ?, CURRENT_DATE, TRUE, 'ACTIVE')
                """, UUID.randomUUID().toString(), studentId.toString(), advisorId.toString());
        jdbc.update("""
                UPDATE notifications SET resolved_at = CURRENT_TIMESTAMP
                WHERE related_student_id = ? AND notification_type = 'ADVISOR_REQUEST'
                  AND resolved_at IS NULL
                """, studentId.toString());
        jdbc.update("""
                INSERT INTO notifications (id, user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'Advisor assigned', 'An advisor has been assigned to you.', FALSE)
                """, UUID.randomUUID().toString(), studentId.toString());
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

    public record AppointmentImpact(UUID appointmentId, UUID studentId, UUID currentAdvisorId,
            LocalDateTime scheduledAt) {}

    public static class AppointmentWarningRequired extends RuntimeException {
        private final List<AppointmentImpact> appointments;

        public AppointmentWarningRequired(List<AppointmentImpact> appointments) {
            this.appointments = appointments;
        }

        public List<AppointmentImpact> appointments() {
            return appointments;
        }
    }
}
