package vn.edu.fpt.faaps.advising.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capacity-ratio and risk-stratified auto-assignment planner.
 * High-risk students (non-ACTIVE status or GPA < 5.00) are distributed first to avoid concentrating
 * them on one advisor; remaining students are balanced by utilization ratio (load / maxAdvisees).
 */
final class SmartAutoAssignPlanner {
    static final BigDecimal GPA_RISK_THRESHOLD = new BigDecimal("5.00");
    private static final double DEFAULT_CAPACITY_WEIGHT = 100.0;

    private SmartAutoAssignPlanner() {}

    static boolean isAtRisk(String academicStatus, BigDecimal currentGpa) {
        if (academicStatus != null && !academicStatus.isBlank() && !"ACTIVE".equalsIgnoreCase(academicStatus)) {
            return true;
        }
        return currentGpa != null
                && currentGpa.compareTo(BigDecimal.ZERO) > 0
                && currentGpa.compareTo(GPA_RISK_THRESHOLD) < 0;
    }

    static Plan plan(List<AdvisorSlot> advisors, List<StudentCandidate> students, boolean allowOverLimit) {
        Map<UUID, Long> loads = new HashMap<>();
        Map<UUID, Long> riskLoads = new HashMap<>();
        Map<UUID, Integer> assignedCounts = new HashMap<>();
        Map<UUID, Integer> riskAssignedCounts = new HashMap<>();
        for (AdvisorSlot advisor : advisors) {
            loads.put(advisor.id(), advisor.activeStudents());
            riskLoads.put(advisor.id(), advisor.activeAtRisk());
            assignedCounts.put(advisor.id(), 0);
            riskAssignedCounts.put(advisor.id(), 0);
        }

        List<StudentCandidate> ordered = students.stream()
                .sorted(Comparator.comparing(StudentCandidate::atRisk).reversed()
                        .thenComparing(s -> s.id().toString()))
                .toList();

        List<AssignmentPair> pairs = new ArrayList<>();
        int unassignedRemaining = 0;
        int totalAtRiskAssigned = 0;

        for (StudentCandidate student : ordered) {
            AdvisorSlot chosen = selectAdvisor(advisors, loads, riskLoads, student.atRisk(), allowOverLimit);
            if (chosen == null) {
                unassignedRemaining++;
                continue;
            }
            pairs.add(new AssignmentPair(student.id(), chosen.id(), student.atRisk()));
            loads.put(chosen.id(), loads.get(chosen.id()) + 1);
            assignedCounts.put(chosen.id(), assignedCounts.get(chosen.id()) + 1);
            if (student.atRisk()) {
                riskLoads.put(chosen.id(), riskLoads.get(chosen.id()) + 1);
                riskAssignedCounts.put(chosen.id(), riskAssignedCounts.get(chosen.id()) + 1);
                totalAtRiskAssigned++;
            }
        }

        List<Map<String, Object>> allocation = advisors.stream().map(advisor -> {
            int added = assignedCounts.get(advisor.id());
            int riskAdded = riskAssignedCounts.get(advisor.id());
            long after = advisor.activeStudents() + added;
            Integer limit = advisor.maxAdvisees();
            int excess = limit == null ? 0 : (int) Math.max(0, after - limit);
            int utilizationPct = (limit != null && limit > 0) ? (int) Math.round((after * 100.0) / limit) : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("advisorId", advisor.id());
            row.put("assigned", added);
            row.put("atRiskAssigned", riskAdded);
            row.put("totalBefore", advisor.activeStudents());
            row.put("totalAfter", after);
            row.put("maxAdvisees", limit);
            row.put("utilizationPct", utilizationPct);
            row.put("overLimitBy", excess);
            row.put("warning", excess > 0);
            return row;
        }).toList();

        boolean anyWarning = allocation.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("warning")));
        return new Plan(pairs, allocation, assignedCounts, pairs.size(), unassignedRemaining,
                totalAtRiskAssigned, ordered.size(), anyWarning);
    }

    private static AdvisorSlot selectAdvisor(
            List<AdvisorSlot> advisors,
            Map<UUID, Long> loads,
            Map<UUID, Long> riskLoads,
            boolean atRiskStudent,
            boolean allowOverLimit) {
        List<AdvisorSlot> underCap = advisors.stream()
                .filter(a -> a.maxAdvisees() == null || loads.get(a.id()) < a.maxAdvisees())
                .toList();
        List<AdvisorSlot> candidates = !underCap.isEmpty() ? underCap : (allowOverLimit ? advisors : List.of());
        if (candidates.isEmpty()) {
            return null;
        }
        Comparator<AdvisorSlot> cmp = atRiskStudent
                ? Comparator.comparingLong((AdvisorSlot a) -> riskLoads.get(a.id()))
                        .thenComparingDouble(a -> utilizationRatio(a, loads.get(a.id())))
                        .thenComparingLong(a -> loads.get(a.id()))
                        .thenComparing(a -> a.id().toString())
                : Comparator.comparingDouble((AdvisorSlot a) -> utilizationRatio(a, loads.get(a.id())))
                        .thenComparingLong(a -> loads.get(a.id()))
                        .thenComparing(a -> a.id().toString());
        return candidates.stream().min(cmp).orElse(null);
    }

    private static double utilizationRatio(AdvisorSlot advisor, long load) {
        Integer limit = advisor.maxAdvisees();
        double denom = (limit != null && limit > 0) ? limit : DEFAULT_CAPACITY_WEIGHT;
        return load / denom;
    }

    record AdvisorSlot(UUID id, Integer maxAdvisees, long activeStudents, long activeAtRisk) {}

    record StudentCandidate(UUID id, boolean atRisk) {}

    record AssignmentPair(UUID studentId, UUID advisorId, boolean atRisk) {}

    record Plan(
            List<AssignmentPair> pairs,
            List<Map<String, Object>> allocation,
            Map<UUID, Integer> assignedByAdvisor,
            int assigned,
            int unassignedRemaining,
            int atRiskAssigned,
            int totalTarget,
            boolean warning) {}
}
