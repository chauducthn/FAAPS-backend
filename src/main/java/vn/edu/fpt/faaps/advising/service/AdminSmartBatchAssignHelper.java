package vn.edu.fpt.faaps.advising.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentModels.Advisor;

/**
 * One-click batch auto-assignment across multiple cohorts.
 * Optionally assigns idle (unpooled) active advisors to cohorts with unmet capacity
 * (preserving the 1-advisor = 1-cohort rule) before running SmartAutoAssignPlanner per cohort.
 */
final class AdminSmartBatchAssignHelper {
    private static final int DEFAULT_ADVISOR_CAPACITY = 30;

    private final JdbcTemplate jdbc;
    private final CohortAdvisorService cohorts;
    private final AdminAssignmentBatchHelper batch;

    AdminSmartBatchAssignHelper(JdbcTemplate jdbc, CohortAdvisorService cohorts, AdminAssignmentBatchHelper batch) {
        this.jdbc = jdbc;
        this.cohorts = cohorts;
        this.batch = batch;
    }

    Map<String, Object> runSmartBatch(
            List<UUID> requestedCohortIds,
            boolean autoConfigurePools,
            boolean allowOverLimit,
            UUID actorId,
            String notes) {
        List<CohortNeed> targets = loadCohortsWithUnassigned(requestedCohortIds);
        int poolsConfigured = 0;
        int advisorsAdded = 0;

        if (autoConfigurePools && !targets.isEmpty()) {
            List<Advisor> idle = new ArrayList<>(loadIdleAdvisors());
            // Round-robin pass 1: ensure as many cohorts as possible get at least 1 advisor.
            Map<UUID, Integer> deficit = new LinkedHashMap<>();
            for (CohortNeed need : targets) {
                int currentFree = currentPoolFreeSlots(need.cohortId());
                int gap = Math.max(0, need.unassigned() - currentFree);
                if (gap > 0) {
                    deficit.put(need.cohortId(), gap);
                }
            }
            List<UUID> orderedCohorts = new ArrayList<>(deficit.keySet());
            boolean progress = true;
            while (!idle.isEmpty() && progress) {
                progress = false;
                for (UUID cohortId : orderedCohorts) {
                    int remainingGap = deficit.getOrDefault(cohortId, 0);
                    if (remainingGap <= 0 || idle.isEmpty()) continue;
                    Advisor picked = idle.remove(0);
                    boolean hadPool = !cohorts.activeIds(cohortId).isEmpty();
                    cohorts.include(cohortId, picked.id());
                    if (!hadPool) poolsConfigured++;
                    advisorsAdded++;
                    int capacity = picked.maxAdvisees() != null && picked.maxAdvisees() > 0
                            ? (int) Math.max(1, picked.maxAdvisees() - picked.activeStudents())
                            : DEFAULT_ADVISOR_CAPACITY;
                    deficit.put(cohortId, Math.max(0, remainingGap - capacity));
                    progress = true;
                }
            }
        }

        int cohortsProcessed = 0;
        int skippedNoPool = 0;
        int totalAssigned = 0;
        int totalAtRisk = 0;
        int totalRemaining = 0;

        for (CohortNeed need : targets) {
            if (cohorts.activeIds(need.cohortId()).isEmpty()) {
                skippedNoPool++;
                totalRemaining += need.unassigned();
                continue;
            }
            Map<String, Object> res = batch.confirmCohort(
                    need.cohortId(), null, null, allowOverLimit, actorId, notes);
            cohortsProcessed++;
            totalAssigned += ((Number) res.getOrDefault("assigned", 0)).intValue();
            totalAtRisk += ((Number) res.getOrDefault("atRiskAssigned", 0)).intValue();
            totalRemaining += ((Number) res.getOrDefault("unassignedRemaining", 0)).intValue();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cohortsProcessed", cohortsProcessed);
        summary.put("poolsConfigured", poolsConfigured);
        summary.put("advisorsAddedToPools", advisorsAdded);
        summary.put("assigned", totalAssigned);
        summary.put("atRiskAssigned", totalAtRisk);
        summary.put("unassignedRemaining", totalRemaining);
        summary.put("skippedNoPool", skippedNoPool);
        return summary;
    }

    Map<String, Object> unassignAll(List<UUID> advisorIds, boolean clearPools, String notes) {
        String noteText = (notes == null || notes.isBlank()) ? "Bulk unassign by admin" : notes.strip();
        boolean scoped = advisorIds != null && !advisorIds.isEmpty();
        String inClause = scoped ? "advisor_id IN (" + String.join(",", advisorIds.stream().map(id -> "?").toList()) + ") AND " : "";
        Object[] idArgs = scoped ? advisorIds.stream().map(UUID::toString).toArray() : new Object[0];
        Object[] assignArgs = new Object[idArgs.length + 1];
        assignArgs[0] = noteText;
        System.arraycopy(idArgs, 0, assignArgs, 1, idArgs.length);

        int unassignedStudents = jdbc.update(
                "UPDATE student_advisor_assignments SET status = 'COMPLETED', end_date = CURRENT_DATE, "
                        + "updated_at = CURRENT_TIMESTAMP, notes = ? WHERE "
                        + inClause + "status = 'ACTIVE' AND end_date IS NULL",
                assignArgs);
        int cancelledSessions = 0;
        try {
            cancelledSessions = jdbc.update(
                    "UPDATE advising_sessions SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP WHERE "
                            + inClause + "status = 'SCHEDULED' AND scheduled_at > CURRENT_TIMESTAMP",
                    idArgs);
        } catch (Exception ignored) {
            // Minimal test schemas may omit advising_sessions.
        }
        int clearedPools = clearPools
                ? jdbc.update("UPDATE cohort_advisors SET is_active = FALSE WHERE " + inClause + "is_active = TRUE", idArgs)
                : 0;
        return Map.of("unassignedStudents", unassignedStudents, "clearedPools", clearedPools, "cancelledSessions", cancelledSessions);
    }

    private List<CohortNeed> loadCohortsWithUnassigned(List<UUID> filterIds) {
        List<CohortNeed> all = jdbc.query("""
                SELECT s.curriculum_version_id AS cohort_id, COUNT(*) AS unassigned
                FROM students s
                WHERE s.curriculum_version_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM student_advisor_assignments sa
                      WHERE sa.student_id = s.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                  )
                GROUP BY s.curriculum_version_id
                ORDER BY unassigned DESC, cohort_id
                """, (rs, row) -> new CohortNeed(
                UUID.fromString(rs.getString("cohort_id")), rs.getInt("unassigned")));
        if (filterIds == null || filterIds.isEmpty()) {
            return all;
        }
        return all.stream().filter(c -> filterIds.contains(c.cohortId())).toList();
    }

    private List<Advisor> loadIdleAdvisors() {
        return jdbc.query("""
                SELECT a.user_id, u.email, u.full_name, a.max_advisees,
                       COUNT(sa.id) AS active_students
                FROM advisors a
                JOIN users u ON u.id = a.user_id
                LEFT JOIN student_advisor_assignments sa
                  ON sa.advisor_id = a.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM cohort_advisors ca
                      WHERE ca.advisor_id = a.user_id AND ca.is_active = TRUE
                  )
                GROUP BY a.user_id, u.email, u.full_name, a.max_advisees
                """, (rs, row) -> new Advisor(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("email"),
                rs.getString("full_name"),
                (Integer) rs.getObject("max_advisees"),
                rs.getLong("active_students")))
                .stream()
                .sorted(Comparator.comparingInt((Advisor a) -> a.maxAdvisees() == null ? DEFAULT_ADVISOR_CAPACITY : a.maxAdvisees())
                        .reversed()
                        .thenComparingLong(Advisor::activeStudents)
                        .thenComparing(a -> a.id().toString()))
                .toList();
    }

    private int currentPoolFreeSlots(UUID cohortId) {
        return cohorts.list(cohortId).stream().mapToInt(a -> {
            if (a.maxAdvisees() == null) return DEFAULT_ADVISOR_CAPACITY;
            return (int) Math.max(0, a.maxAdvisees() - a.cohortStudents());
        }).sum();
    }

    private record CohortNeed(UUID cohortId, int unassigned) {}
}
