package vn.edu.fpt.faaps.advising.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.edu.fpt.faaps.advising.repository.AssignmentReadRepository;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Advisor;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Preview;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Result;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.AdvisorSlot;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.AssignmentPair;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.Plan;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.StudentCandidate;

class AdminAssignmentBatchHelper {
    private final AssignmentReadRepository reads;
    private final CohortAdvisorService cohorts;
    private final AdminAssignmentSupport support;

    AdminAssignmentBatchHelper(AssignmentReadRepository reads, CohortAdvisorService cohorts,
            AdminAssignmentSupport support) {
        this.reads = reads;
        this.cohorts = cohorts;
        this.support = support;
    }

    Preview preview(String cohort, List<UUID> advisorIds, List<UUID> studentIds) {
        List<Advisor> selected = support.selectedAdvisors(advisorIds);
        List<UUID> available = reads.students(cohort, true).stream().map(student -> student.id()).toList();
        List<UUID> targets = (studentIds != null && !studentIds.isEmpty())
                ? support.selectedTargets(studentIds, available)
                : available;
        Plan plan = buildPlan(selected, targets.stream().map(id -> new StudentCandidate(id, false)).toList(), true);
        return new Preview(cohort, targets.size(), plan.assignedByAdvisor());
    }

    Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds, boolean allowOverLimit) {
        List<Advisor> selected = support.selectedAdvisorsForCohort(cohortId, advisorIds);
        List<StudentCandidate> targets = resolveCohortCandidates(cohortId, studentIds);
        Plan plan = buildPlan(selected, targets, allowOverLimit);
        return allocationResponse(cohortId, plan, allowOverLimit);
    }

    Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds,
            boolean allowOverLimit, UUID actorId, String notes) {
        cohorts.lockCohort(cohortId);
        List<Advisor> initial = support.selectedAdvisorsForCohort(cohortId, advisorIds);
        for (UUID advisorId : initial.stream().map(advisor -> advisor.id()).sorted().toList()) {
            support.lockAdvisor(advisorId);
        }
        List<Advisor> selected = support.selectedAdvisorsForCohort(cohortId, advisorIds);

        List<StudentCandidate> initialTargets = resolveCohortCandidates(cohortId, studentIds);
        for (StudentCandidate candidate : initialTargets) {
            support.lockStudent(candidate.id());
        }
        List<StudentCandidate> targets = resolveCohortCandidates(cohortId, studentIds);
        Plan plan = buildPlan(selected, targets, allowOverLimit);
        for (AssignmentPair pair : plan.pairs()) {
            support.insertAssignment(pair.studentId(), pair.advisorId(), actorId, notes);
        }
        return allocationResponse(cohortId, plan, allowOverLimit);
    }

    Result confirm(String cohort, List<UUID> advisorIds, List<UUID> studentIds, UUID actorId, String notes) {
        for (UUID advisorId : advisorIds.stream().sorted().toList()) {
            support.lockAdvisor(advisorId);
        }
        List<Advisor> selected = support.selectedAdvisors(advisorIds);
        List<UUID> targetIds = resolveLegacyTargets(cohort, studentIds);
        for (UUID studentId : targetIds) {
            support.lockStudent(studentId);
        }
        if (studentIds != null && !studentIds.isEmpty()) {
            support.selectedTargets(studentIds, reads.students(cohort, true).stream().map(student -> student.id()).toList());
        } else {
            targetIds = reads.students(cohort, true).stream().map(student -> student.id()).sorted().toList();
        }
        Plan plan = buildPlan(selected, targetIds.stream().map(id -> new StudentCandidate(id, false)).toList(), true);
        for (AssignmentPair pair : plan.pairs()) {
            support.insertAssignment(pair.studentId(), pair.advisorId(), actorId, notes);
        }
        return new Result(plan.assigned(), plan.assignedByAdvisor());
    }

    private List<StudentCandidate> resolveCohortCandidates(UUID cohortId, List<UUID> studentIds) {
        List<StudentCandidate> available = support.unassignedCandidates(cohortId);
        if (studentIds == null || studentIds.isEmpty()) {
            return available;
        }
        List<UUID> validated = support.selectedTargets(studentIds, available.stream().map(candidate -> candidate.id()).toList());
        Set<UUID> allowed = Set.copyOf(validated);
        return available.stream().filter(c -> allowed.contains(c.id())).toList();
    }

    private List<UUID> resolveLegacyTargets(String cohort, List<UUID> studentIds) {
        List<UUID> available = reads.students(cohort, true).stream().map(student -> student.id()).toList();
        if (studentIds != null && !studentIds.isEmpty()) {
            return support.selectedTargets(studentIds, available);
        }
        return available.stream().sorted().toList();
    }

    private Plan buildPlan(List<Advisor> advisors, List<StudentCandidate> targets, boolean allowOverLimit) {
        Map<UUID, Long> riskCounts = support.activeAtRiskCounts(advisors.stream().map(advisor -> advisor.id()).toList());
        List<AdvisorSlot> slots = advisors.stream()
                .map(a -> new AdvisorSlot(a.id(), a.maxAdvisees(), a.activeStudents(), riskCounts.getOrDefault(a.id(), 0L)))
                .toList();
        return SmartAutoAssignPlanner.plan(slots, targets, allowOverLimit);
    }

    private Map<String, Object> allocationResponse(UUID cohortId, Plan plan, boolean allowOverLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cohortId", cohortId);
        body.put("assigned", plan.assigned());
        body.put("unassignedRemaining", plan.unassignedRemaining());
        body.put("atRiskAssigned", plan.atRiskAssigned());
        body.put("totalTarget", plan.totalTarget());
        body.put("allowOverLimit", allowOverLimit);
        body.put("allocation", plan.allocation());
        body.put("warning", plan.warning());
        return body;
    }
}
