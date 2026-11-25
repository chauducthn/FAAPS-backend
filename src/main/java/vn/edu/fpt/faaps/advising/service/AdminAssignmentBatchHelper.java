package vn.edu.fpt.faaps.advising.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.edu.fpt.faaps.advising.repository.AssignmentReadRepository;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Advisor;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Conflict;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Preview;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Result;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentService.Student;

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
        List<UUID> available = reads.students(cohort, true).stream().map(Student::id).toList();
        int count = (studentIds != null && !studentIds.isEmpty())
                ? support.selectedTargets(studentIds, available).size()
                : available.size();
        return new Preview(cohort, count, distribute(selected, count));
    }

    Map<String, Object> previewCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds) {
        List<Advisor> selected = support.selectedAdvisorsForCohort(cohortId, advisorIds);
        List<UUID> available = support.unassignedIds(cohortId);
        int count = (studentIds != null && !studentIds.isEmpty())
                ? support.selectedTargets(studentIds, available).size()
                : available.size();
        return allocationResponse(cohortId, count, selected, distribute(selected, count));
    }

    Map<String, Object> confirmCohort(UUID cohortId, List<UUID> advisorIds, List<UUID> studentIds,
            UUID actorId, String notes) {
        cohorts.lockCohort(cohortId);
        List<Advisor> initial = support.selectedAdvisorsForCohort(cohortId, advisorIds);
        for (UUID advisorId : initial.stream().map(Advisor::id).sorted().toList()) {
            support.lockAdvisor(advisorId);
        }
        List<Advisor> selected = support.selectedAdvisorsForCohort(cohortId, advisorIds);

        List<UUID> targetIds = resolveCohortTargets(cohortId, studentIds);
        for (UUID studentId : targetIds) {
            support.lockStudent(studentId);
        }
        if (studentIds != null && !studentIds.isEmpty()) {
            support.selectedTargets(studentIds, support.unassignedIds(cohortId));
        } else {
            targetIds = support.unassignedIds(cohortId);
        }

        Map<UUID, Integer> allocation = assignRoundRobin(selected, targetIds, actorId, notes);
        return allocationResponse(cohortId, targetIds.size(), selected, allocation);
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
            support.selectedTargets(studentIds, reads.students(cohort, true).stream().map(Student::id).toList());
        } else {
            targetIds = reads.students(cohort, true).stream().map(Student::id).sorted().toList();
        }

        Map<UUID, Integer> assignedCounts = assignRoundRobin(selected, targetIds, actorId, notes);
        return new Result(targetIds.size(), assignedCounts);
    }

    private List<UUID> resolveCohortTargets(UUID cohortId, List<UUID> studentIds) {
        if (studentIds != null && !studentIds.isEmpty()) {
            return support.selectedTargets(studentIds, support.unassignedIds(cohortId));
        }
        return support.unassignedIds(cohortId);
    }

    private List<UUID> resolveLegacyTargets(String cohort, List<UUID> studentIds) {
        List<UUID> available = reads.students(cohort, true).stream().map(Student::id).toList();
        if (studentIds != null && !studentIds.isEmpty()) {
            return support.selectedTargets(studentIds, available);
        }
        return available.stream().sorted().toList();
    }

    private Map<UUID, Integer> assignRoundRobin(List<Advisor> selected, List<UUID> targetIds,
            UUID actorId, String notes) {
        Map<UUID, Integer> allocation = new HashMap<>();
        Map<UUID, Long> loads = new HashMap<>();
        for (Advisor advisor : selected) {
            allocation.put(advisor.id(), 0);
            loads.put(advisor.id(), advisor.activeStudents());
        }
        for (UUID studentId : targetIds) {
            Advisor next = nextAdvisor(selected, loads);
            support.insertAssignment(studentId, next.id(), actorId, notes);
            loads.put(next.id(), loads.get(next.id()) + 1);
            allocation.put(next.id(), allocation.get(next.id()) + 1);
        }
        return allocation;
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

    private Map<String, Object> allocationResponse(UUID cohortId, int count,
            List<Advisor> advisors, Map<UUID, Integer> allocation) {
        List<Map<String, Object>> limits = advisors.stream().map(advisor -> {
            long after = advisor.activeStudents() + allocation.get(advisor.id());
            int excess = advisor.maxAdvisees() == null ? 0
                    : (int) Math.max(0, after - advisor.maxAdvisees());
            return Map.<String, Object>of(
                    "advisorId", advisor.id(), "assigned", allocation.get(advisor.id()),
                    "totalBefore", advisor.activeStudents(), "totalAfter", after,
                    "overLimitBy", excess, "warning", excess > 0);
        }).toList();
        return Map.of("cohortId", cohortId, "assigned", count, "allocation", limits,
                "warning", limits.stream().anyMatch(item -> (boolean) item.get("warning")));
    }
}
