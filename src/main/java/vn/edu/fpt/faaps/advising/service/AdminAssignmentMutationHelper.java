package vn.edu.fpt.faaps.advising.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.AppointmentImpact;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.AppointmentWarningRequired;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.AssignmentError;

class AdminAssignmentMutationHelper {
    private final JdbcTemplate jdbc;
    private final CohortAdvisorService cohorts;
    private final AdminAssignmentSupport support;

    AdminAssignmentMutationHelper(JdbcTemplate jdbc, CohortAdvisorService cohorts, AdminAssignmentSupport support) {
        this.jdbc = jdbc;
        this.cohorts = cohorts;
        this.support = support;
    }

    int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation,
            UUID actorId, String notes) {
        support.validateIds(studentIds);
        support.lockAdvisor(advisorId);
        support.requireAdvisor(advisorId);
        List<UUID> sortedStudents = studentIds.stream().sorted().toList();
        for (UUID studentId : sortedStudents) {
            support.lockStudent(studentId);
        }
        for (UUID studentId : sortedStudents) {
            if (advisorId.toString().equals(support.activeAdvisor(studentId))) {
                continue;
            }
            UUID cohortId = jdbc.queryForObject("SELECT curriculum_version_id FROM students WHERE user_id = ?",
                    (result, row) -> UUID.fromString(result.getString(1)), studentId.toString());
            List<UUID> pool = cohorts.activeIds(cohortId);
            if (pool.isEmpty()) {
                throw new AssignmentError("COHORT_ADVISORS_NOT_CONFIGURED",
                        "No advisors are configured for this cohort");
            }
            if (!pool.contains(advisorId)) {
                throw new AssignmentError("ADVISOR_NOT_IN_COHORT",
                        "Selected advisor is not configured for this student's cohort");
            }
        }
        List<AppointmentImpact> impacts = appointmentImpacts(sortedStudents, advisorId);
        if (!impacts.isEmpty() && !acknowledgeAppointmentCancellation) {
            throw new AppointmentWarningRequired(impacts);
        }
        int changed = 0;
        for (UUID studentId : sortedStudents) {
            String activeAdvisor = support.activeAdvisor(studentId);
            if (advisorId.toString().equals(activeAdvisor)) {
                continue;
            }
            if (activeAdvisor != null) {
                transferExistingAssignment(studentId, activeAdvisor, advisorId);
            }
            support.insertAssignment(studentId, advisorId, actorId, notes);
            changed++;
        }
        return changed;
    }

    List<AppointmentImpact> appointmentImpacts(List<UUID> studentIds, UUID newAdvisorId) {
        support.validateIds(studentIds);
        List<AppointmentImpact> impacts = new ArrayList<>();
        for (UUID studentId : studentIds) {
            String currentAdvisorId = support.activeAdvisor(studentId);
            if (currentAdvisorId == null
                    || (newAdvisorId != null && currentAdvisorId.equals(newAdvisorId.toString()))) {
                continue;
            }
            impacts.addAll(jdbc.query("""
                    SELECT id, student_id, advisor_id, scheduled_at FROM advising_sessions
                    WHERE student_id = ? AND advisor_id = ? AND status = 'SCHEDULED'
                      AND scheduled_at > CURRENT_TIMESTAMP ORDER BY scheduled_at
                    """, (result, row) -> new AppointmentImpact(
                    UUID.fromString(result.getString("id")),
                    UUID.fromString(result.getString("student_id")),
                    UUID.fromString(result.getString("advisor_id")),
                    result.getTimestamp("scheduled_at").toLocalDateTime()),
                    studentId.toString(), currentAdvisorId));
        }
        return impacts;
    }

    int unassign(List<UUID> studentIds, boolean acknowledgeAppointmentCancellation, UUID actorId, String notes) {
        support.validateIds(studentIds);
        List<UUID> sortedStudents = studentIds.stream().sorted().toList();
        for (UUID studentId : sortedStudents) {
            support.lockStudent(studentId);
        }
        List<AppointmentImpact> impacts = appointmentImpacts(sortedStudents, null);
        if (!impacts.isEmpty() && !acknowledgeAppointmentCancellation) {
            throw new AppointmentWarningRequired(impacts);
        }
        int changed = 0;
        for (UUID studentId : sortedStudents) {
            String advisorId = support.activeAdvisor(studentId);
            if (advisorId == null) {
                continue;
            }
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
            int cancelled = cancelFutureSessions(studentId, advisorId);
            if (cancelled > 0) {
                notifyStudent(studentId, "Appointment cancelled",
                        "Your future appointment was cancelled after advisor unassignment.");
            }
            notifyStudent(studentId, "Advisor unassigned", "You currently have no assigned advisor.");
        }
        return changed;
    }

    private void transferExistingAssignment(UUID studentId, String previousAdvisorId, UUID newAdvisorId) {
        jdbc.update("""
                UPDATE student_advisor_assignments SET status = 'TRANSFERRED',
                    end_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, studentId.toString());
        jdbc.update("""
                UPDATE advising_requests SET advisor_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                """, newAdvisorId.toString(), studentId.toString());
        int cancelled = cancelFutureSessions(studentId, previousAdvisorId);
        if (cancelled > 0) {
            notifyStudent(studentId, "Appointment cancelled",
                    "Your future appointment was cancelled after advisor reassignment. Please book a new slot.");
        }
    }

    private int cancelFutureSessions(UUID studentId, String advisorId) {
        return jdbc.update("""
                UPDATE advising_sessions SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND advisor_id = ?
                  AND status = 'SCHEDULED' AND scheduled_at > CURRENT_TIMESTAMP
                """, studentId.toString(), advisorId);
    }

    private void notifyStudent(UUID studentId, String title, String message) {
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', ?, ?, FALSE)
                """, UUID.randomUUID().toString(), studentId.toString(), title, message);
    }
}
