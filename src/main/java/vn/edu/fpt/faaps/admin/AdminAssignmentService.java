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

    public Page<Advisor> advisors(int page, int size, String search, String status) {
        validatePage(page, size);
        String term = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
        String state = status == null || status.isBlank() ? null : status.toUpperCase();
        if (state != null && !List.of("ACTIVE", "INACTIVE", "ON_LEAVE").contains(state)) {
            throw new IllegalArgumentException("status must be ACTIVE, INACTIVE, or ON_LEAVE");
        }
        String where = " WHERE (? IS NULL OR a.status = ?) AND (? IS NULL OR LOWER(a.advisor_code) LIKE ? OR LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM advisors a JOIN users u ON u.id = a.user_id" + where,
                Long.class, state, state, term, term, term, term);
        List<Advisor> items = jdbc.query("""
                SELECT a.user_id, a.advisor_code, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS active_students
                FROM advisors a JOIN users u ON u.id = a.user_id
                LEFT JOIN student_advisor_assignments sa ON sa.advisor_id = a.user_id
                    AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                """ + where + " GROUP BY a.user_id, a.advisor_code, u.full_name, a.max_advisees "
                + "ORDER BY u.full_name LIMIT ? OFFSET ?", this::mapAdvisor,
                state, state, term, term, term, term, size, page * size);
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
        String from = " FROM students s JOIN users u ON u.id=s.user_id JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id JOIN majors m ON m.id=cv.major_id LEFT JOIN student_advisor_assignments sa ON sa.student_id=s.user_id AND sa.status='ACTIVE' AND sa.end_date IS NULL LEFT JOIN users advisor_user ON advisor_user.id=sa.advisor_id";
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
        Map<String, Object> profile = jdbc.queryForMap("SELECT u.email,u.avatar_url,a.status FROM advisors a JOIN users u ON u.id=a.user_id WHERE a.user_id=?", advisorId.toString());
        List<Cohort> allCohorts = jdbc.query("SELECT cv.id AS cohort_id,cv.version AS cohort,m.code AS major_code,COUNT(s.user_id) AS students FROM curriculum_versions cv JOIN majors m ON m.id=cv.major_id LEFT JOIN students s ON s.curriculum_version_id=cv.id GROUP BY cv.id,cv.version,m.code ORDER BY m.code,cv.version",
                (rs, row) -> new Cohort(UUID.fromString(rs.getString("cohort_id")), rs.getString("cohort"), rs.getString("major_code"), rs.getLong("students")));
        List<Cohort> configured = jdbc.query("SELECT cv.id AS cohort_id,cv.version AS cohort,m.code AS major_code,COUNT(s.user_id) AS students FROM cohort_advisors ca JOIN curriculum_versions cv ON cv.id=ca.cohort_id JOIN majors m ON m.id=cv.major_id LEFT JOIN students s ON s.curriculum_version_id=cv.id WHERE ca.advisor_id=? AND ca.is_active=TRUE GROUP BY cv.id,cv.version,m.code ORDER BY m.code,cv.version",
                (rs, row) -> new Cohort(UUID.fromString(rs.getString("cohort_id")), rs.getString("cohort"), rs.getString("major_code"), rs.getLong("students")), advisorId.toString());
        return new AdvisorDetail(advisor, (String) profile.get("email"), (String) profile.get("avatar_url"),
                (String) profile.get("status"), students, allCohorts, configured);
    }

    public List<History> history(UUID studentId) {
        return jdbc.query("""
                SELECT sa.id, sa.advisor_id, a.advisor_code, u.full_name AS advisor_name,
                       sa.assigned_date, sa.assigned_at, sa.assigned_by_user_id,
                       actor.full_name AS actor_name, sa.notes, sa.end_date, sa.status
                FROM student_advisor_assignments sa JOIN users u ON u.id = sa.advisor_id
                JOIN advisors a ON a.user_id=sa.advisor_id
                LEFT JOIN users actor ON actor.id=sa.assigned_by_user_id
                WHERE sa.student_id = ? ORDER BY sa.assigned_at DESC, sa.id DESC
                """, (result, row) -> new History(result.getString("id"),
                result.getString("advisor_id"), result.getString("advisor_code"), result.getString("advisor_name"),
                result.getDate("assigned_date").toLocalDate(), result.getTimestamp("assigned_at") == null
                        ? null : result.getTimestamp("assigned_at").toLocalDateTime(),
                result.getString("assigned_by_user_id"), result.getString("actor_name"), result.getString("notes"),
                result.getDate("end_date") == null ? null : result.getDate("end_date").toLocalDate(),
                result.getString("status")), studentId.toString());
    }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId) {
        return assign(studentIds, advisorId, false);
    }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation) {
        return assign(studentIds, advisorId, acknowledgeAppointmentCancellation, null, null);
    }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation,
            UUID actorId, String notes) {
        validateIds(studentIds);
        lockAdvisor(advisorId);
        Advisor advisor = requireAdvisor(advisorId);
        long load = advisor.activeStudents();
        int changed = 0;
        List<UUID> sortedStudents = studentIds.stream().sorted().toList();
        for (UUID studentId : sortedStudents) {
            lockStudent(studentId);
        }
        for (UUID studentId : sortedStudents) {
            if (advisorId.toString().equals(activeAdvisor(studentId))) continue;
            UUID cohortId = jdbc.queryForObject("SELECT curriculum_version_id FROM students WHERE user_id = ?",
                    (result, row) -> UUID.fromString(result.getString(1)), studentId.toString());
            List<UUID> pool = cohorts.activeIds(cohortId);
            if (pool.isEmpty()) throw new AssignmentError("COHORT_ADVISORS_NOT_CONFIGURED", "No advisors are configured for this cohort");
            if (!pool.contains(advisorId)) throw new AssignmentError("ADVISOR_NOT_IN_COHORT", "Selected advisor is not configured for this student's cohort");
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
            insertAssignment(studentId, advisorId, actorId, notes);
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
            if (currentAdvisorId == null || (newAdvisorId != null && currentAdvisorId.equals(newAdvisorId.toString()))) continue;
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

    @Transactional
    public int unassign(List<UUID> studentIds, boolean acknowledgeAppointmentCancellation, UUID actorId, String notes) {
        validateIds(studentIds);
        List<UUID> sortedStudents = studentIds.stream().sorted().toList();
        for (UUID studentId : sortedStudents) lockStudent(studentId);
        List<AppointmentImpact> impacts = appointmentImpacts(sortedStudents, null);
        if (!impacts.isEmpty() && !acknowledgeAppointmentCancellation) {
            throw new AppointmentWarningRequired(impacts);
        }
        int changed = 0;
        for (UUID studentId : sortedStudents) {
            String advisorId = activeAdvisor(studentId);
            if (advisorId == null) continue;
            changed += jdbc.update("""
                    UPDATE student_advisor_assignments SET status = 'COMPLETED',
                        end_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP,
                        notes = CASE WHEN ? IS NULL THEN notes ELSE ? END
                    WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                    """, notes, notes, studentId.toString());
            jdbc.update("""
                    UPDATE advising_requests SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
                    WHERE student_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                    """, studentId.toString());
            int cancelled = jdbc.update("""
                    UPDATE advising_sessions SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
                    WHERE student_id = ? AND advisor_id = ?
                      AND status = 'SCHEDULED' AND scheduled_at > CURRENT_TIMESTAMP
                    """, studentId.toString(), advisorId);
            if (cancelled > 0) {
                jdbc.update("""
                        INSERT INTO notifications (id, user_id, notification_type, title, message, is_read)
                        VALUES (?, ?, 'INFO', 'Appointment cancelled',
                            'Your future appointment was cancelled after advisor unassignment.', FALSE)
                        """, UUID.randomUUID().toString(), studentId.toString());
            }
            jdbc.update("""
                    INSERT INTO notifications (id, user_id, notification_type, title, message, is_read)
                    VALUES (?, ?, 'INFO', 'Advisor unassigned',
                        'You currently have no assigned advisor.', FALSE)
                    """, UUID.randomUUID().toString(), studentId.toString());
        }
        return changed;
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
        return confirmCohort(cohortId, advisorIds, null, null);
    }

    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, UUID actorId, String notes) {
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
            insertAssignment(studentId, advisor.id(), actorId, notes);
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
                    "totalBefore", advisor.activeStudents(), "totalAfter", after, "overLimitBy", excess, "warning", excess > 0);
        }).toList();
        return Map.of("cohortId", cohortId, "assigned", count, "allocation", limits,
                "warning", limits.stream().anyMatch(item -> (boolean) item.get("warning")));
    }

    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds) {
        return confirm(cohort, advisorIds, null, null);
    }

    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds, UUID actorId, String notes) {
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
            insertAssignment(student.id(), next.id(), actorId, notes);
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
        if (pool.isEmpty()) throw new AssignmentError("COHORT_ADVISORS_NOT_CONFIGURED", "No advisors are configured for this cohort");
        if (!pool.containsAll(selected)) throw new AssignmentError("ADVISOR_NOT_IN_COHORT", "Selected advisor is not configured for this cohort");
        return selectedAdvisors(selected);
    }

    private void validateIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("ids must be unique and contain 1-100 values");
        }
    }

    private Advisor requireAdvisor(UUID id) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM advisors a JOIN users u ON u.id=a.user_id WHERE a.user_id=? AND a.status='ACTIVE' AND u.status='ACTIVE'", Integer.class, id.toString());
        if (active == null || active == 0) throw new AssignmentError("ADVISOR_INACTIVE", "Advisor is not active");
        return advisors().stream().filter(advisor -> advisor.id().equals(id))
                .findFirst().orElseThrow(() -> new AssignmentError("ADVISOR_INACTIVE", "Advisor is not active"));
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
        insertAssignment(studentId, advisorId, null, null);
    }

    void insertAssignment(UUID studentId, UUID advisorId, UUID actorId, String notes) {
        jdbc.update("""
                INSERT INTO student_advisor_assignments
                    (id, student_id, advisor_id, assigned_date, assigned_at, assigned_by_user_id, notes, is_primary, status)
                VALUES (?, ?, ?, CURRENT_DATE, CURRENT_TIMESTAMP, ?, ?, TRUE, 'ACTIVE')
                """, UUID.randomUUID().toString(), studentId.toString(), advisorId.toString(),
                actorId == null ? null : actorId.toString(), notes);
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

    public record Advisor(UUID id, String advisorCode, String fullName,
            Integer maxAdvisees, long activeStudents) {
    }

    public record Cohort(UUID cohortId, String cohort, String majorCode, long students) {
    }

    public record Student(UUID id, String studentCode, String fullName, UUID cohortId,
            String cohort, String majorCode, String advisorId, String advisorName, LocalDate assignedDate) {
    }

    public record AdvisorDetail(Advisor advisor, String email, String avatarUrl, String status,
            List<Student> students, List<Cohort> cohorts, List<Cohort> configuredCohorts) {
    }

    public record History(String id, String advisorId, String advisorCode, String advisorName,
            LocalDate assignedDate, LocalDateTime assignedAt, String assignedByUserId,
            String assignedByName, String notes, LocalDate endDate, String status) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements) {
        public long totalPages() { return (totalElements + size - 1) / size; }
    }
    public static class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
    public static class AssignmentError extends RuntimeException {
        private final String code;
        public AssignmentError(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
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
