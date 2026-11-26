package vn.edu.fpt.faaps.advising.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatically distributes selected idle advisors across cohorts that need pool capacity,
 * without assigning students yet (preserving the 1 advisor = 1 major + 1 cohort rule).
 */
@Service
public class CohortAdvisorAutoAssignHelper {
    private static final int DEFAULT_CAPACITY = 30;
    private static final int MAX_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final CohortAdvisorService cohorts;

    public CohortAdvisorAutoAssignHelper(JdbcTemplate jdbc, CohortAdvisorService cohorts) {
        this.jdbc = jdbc;
        this.cohorts = cohorts;
    }

    @Transactional
    public Map<String, Object> autoAssignCohorts(List<UUID> advisorIds) {
        if (advisorIds == null || advisorIds.isEmpty() || advisorIds.size() > MAX_BATCH
                || advisorIds.stream().anyMatch(Objects::isNull)
                || advisorIds.stream().distinct().count() != advisorIds.size()) {
            throw new IllegalArgumentException("advisorIds must contain 1 to 500 unique values");
        }
        List<AdvisorCandidate> candidates = loadRequestedAdvisors(advisorIds);
        List<AdvisorCandidate> idle = new ArrayList<>();
        int skippedAlreadyAssigned = 0;
        for (AdvisorCandidate candidate : candidates) {
            if (candidate.hasCohort()) {
                skippedAlreadyAssigned++;
            } else {
                idle.add(candidate);
            }
        }
        idle.sort(Comparator.comparingInt((AdvisorCandidate a) -> a.capacity()).reversed()
                .thenComparingLong(AdvisorCandidate::activeStudents)
                .thenComparing(a -> a.id().toString()));

        Map<UUID, Integer> deficit = loadCohortDeficits();
        List<UUID> orderedCohorts = new ArrayList<>(deficit.keySet());
        Set<UUID> touchedCohorts = new LinkedHashSet<>();
        int assigned = 0;
        boolean progress = true;

        while (!idle.isEmpty() && progress) {
            progress = false;
            for (UUID cohortId : orderedCohorts) {
                int remaining = deficit.getOrDefault(cohortId, 0);
                if (remaining <= 0 || idle.isEmpty()) continue;
                AdvisorCandidate picked = idle.remove(0);
                cohorts.include(cohortId, picked.id());
                touchedCohorts.add(cohortId);
                assigned++;
                deficit.put(cohortId, Math.max(0, remaining - picked.capacity()));
                progress = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assigned", assigned);
        result.put("skippedAlreadyAssigned", skippedAlreadyAssigned);
        result.put("skippedNoNeed", idle.size());
        result.put("cohortsConfigured", touchedCohorts.size());
        return result;
    }

    private List<AdvisorCandidate> loadRequestedAdvisors(List<UUID> advisorIds) {
        String placeholders = String.join(",", advisorIds.stream().map(id -> "?").toList());
        Object[] args = advisorIds.stream().map(UUID::toString).toArray();
        String sql = """
                SELECT a.user_id, a.max_advisees, COUNT(sa.id) AS active_students,
                       EXISTS (
                           SELECT 1 FROM cohort_advisors ca
                           WHERE ca.advisor_id = a.user_id AND ca.is_active = TRUE
                       ) AS has_cohort
                FROM advisors a
                JOIN users u ON u.id = a.user_id
                LEFT JOIN student_advisor_assignments sa
                  ON sa.advisor_id = a.user_id AND sa.status = 'ACTIVE' AND sa.end_date IS NULL
                WHERE a.status = 'ACTIVE' AND u.status = 'ACTIVE'
                  AND a.user_id IN (%s)
                GROUP BY a.user_id, a.max_advisees
                """.formatted(placeholders);
        return jdbc.query(sql, (rs, row) -> new AdvisorCandidate(
                UUID.fromString(rs.getString("user_id")),
                (Integer) rs.getObject("max_advisees"),
                rs.getLong("active_students"),
                rs.getBoolean("has_cohort")), args);
    }

    private Map<UUID, Integer> loadCohortDeficits() {
        List<CohortNeed> needs = jdbc.query("""
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
        Map<UUID, Integer> deficit = new LinkedHashMap<>();
        for (CohortNeed need : needs) {
            int free = cohorts.list(need.cohortId()).stream().mapToInt(a -> {
                if (a.maxAdvisees() == null) return DEFAULT_CAPACITY;
                return (int) Math.max(0, a.maxAdvisees() - a.cohortStudents());
            }).sum();
            int gap = Math.max(0, need.unassigned() - free);
            if (gap > 0) deficit.put(need.cohortId(), gap);
        }
        return deficit;
    }

    private record AdvisorCandidate(UUID id, Integer maxAdvisees, long activeStudents, boolean hasCohort) {
        int capacity() {
            if (maxAdvisees == null || maxAdvisees <= 0) return DEFAULT_CAPACITY;
            return (int) Math.max(1, maxAdvisees - activeStudents);
        }
    }

    private record CohortNeed(UUID cohortId, int unassigned) {}
}
