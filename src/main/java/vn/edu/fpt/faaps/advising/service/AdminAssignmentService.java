package vn.edu.fpt.faaps.advising.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.advising.repository.AssignmentReadRepository;

@Service
public class AdminAssignmentService implements AdminAssignmentModels {
    private final AssignmentReadRepository reads;
    private final AdminAssignmentSupport support;
    private final AdminAssignmentMutationHelper mutations;
    private final AdminAssignmentBatchHelper batch;

    public AdminAssignmentService(JdbcTemplate jdbc, CohortAdvisorService cohorts) {
        this.reads = new AssignmentReadRepository(jdbc);
        this.support = new AdminAssignmentSupport(jdbc, this.reads, cohorts);
        this.mutations = new AdminAssignmentMutationHelper(jdbc, cohorts, this.support);
        this.batch = new AdminAssignmentBatchHelper(this.reads, cohorts, this.support);
    }

    public List<Advisor> advisors() { return reads.advisors(); }
    public Page<Advisor> advisors(int page, int size, String search, String status) {
        return reads.advisors(page, size, search, status);
    }
    public List<Cohort> cohorts() { return reads.cohorts(); }
    public List<Student> students(String cohort, boolean unassignedOnly) {
        return reads.students(cohort, unassignedOnly);
    }
    public Page<Student> students(UUID cohortId, String cohort, boolean unassignedOnly,
            int page, int size, String search, String advisorId, String status) {
        return reads.students(cohortId, cohort, unassignedOnly, page, size, search, advisorId, status);
    }
    public AdvisorDetail advisor(UUID advisorId) { return reads.advisor(advisorId); }
    public List<History> history(UUID studentId) { return reads.history(studentId); }

    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId) { return assign(studentIds, advisorId, false); }
    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation) {
        return assign(studentIds, advisorId, acknowledgeAppointmentCancellation, null, null);
    }
    @Transactional
    public int assign(List<UUID> studentIds, UUID advisorId, boolean acknowledgeAppointmentCancellation,
            UUID actorId, String notes) {
        return mutations.assign(studentIds, advisorId, acknowledgeAppointmentCancellation, actorId, notes);
    }
    public List<AppointmentImpact> appointmentImpacts(List<UUID> studentIds, UUID newAdvisorId) {
        return mutations.appointmentImpacts(studentIds, newAdvisorId);
    }
    @Transactional
    public int unassign(List<UUID> studentIds, boolean acknowledgeAppointmentCancellation, UUID actorId, String notes) {
        return mutations.unassign(studentIds, acknowledgeAppointmentCancellation, actorId, notes);
    }

    public Preview preview(String cohort, List<UUID> advisorIds) { return preview(cohort, advisorIds, null); }
    public Preview preview(String cohort, List<UUID> advisorIds, List<UUID> studentIds) {
        return batch.preview(cohort, advisorIds, studentIds);
    }
    public Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds) {
        return previewCohort(cohortId, advisorIds, null, true);
    }
    public Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds) {
        return previewCohort(cohortId, advisorIds, studentIds, true);
    }
    public Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds,
            boolean allowOverLimit) {
        return batch.previewCohort(cohortId, advisorIds, studentIds, allowOverLimit);
    }

    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds) {
        return confirmCohort(cohortId, advisorIds, null, true, null, null);
    }
    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, UUID actorId, String notes) {
        return confirmCohort(cohortId, advisorIds, null, true, actorId, notes);
    }
    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds,
            UUID actorId, String notes) {
        return confirmCohort(cohortId, advisorIds, studentIds, true, actorId, notes);
    }
    @Transactional
    public Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds,
            boolean allowOverLimit, UUID actorId, String notes) {
        return batch.confirmCohort(cohortId, advisorIds, studentIds, allowOverLimit, actorId, notes);
    }
    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds) { return confirm(cohort, advisorIds, null, null, null); }
    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds, UUID actorId, String notes) {
        return confirm(cohort, advisorIds, null, actorId, notes);
    }
    @Transactional
    public Result confirm(String cohort, List<UUID> advisorIds, List<UUID> studentIds, UUID actorId, String notes) {
        return batch.confirm(cohort, advisorIds, studentIds, actorId, notes);
    }

    void lockAdvisor(UUID id) { support.lockAdvisor(id); }
    void lockStudent(UUID id) { support.lockStudent(id); }
    String activeAdvisor(UUID studentId) { return support.activeAdvisor(studentId); }
    void insertAssignment(UUID studentId, UUID advisorId) { support.insertAssignment(studentId, advisorId, null, null); }
    void insertAssignment(UUID studentId, UUID advisorId, UUID actorId, String notes) {
        support.insertAssignment(studentId, advisorId, actorId, notes);
    }

}
