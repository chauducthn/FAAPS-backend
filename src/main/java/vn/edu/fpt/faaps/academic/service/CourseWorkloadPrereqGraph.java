package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cycle-safe prerequisite graph for computing direct prerequisites, chain depth, and unlock counts. */
final class CourseWorkloadPrereqGraph {
    private static final Logger log = LoggerFactory.getLogger(CourseWorkloadPrereqGraph.class);

    private final Map<String, List<String>> prereqs;
    private final Map<String, Integer> unlocks;
    private final Map<String, Integer> depthCache = new HashMap<>();

    private CourseWorkloadPrereqGraph(Map<String, List<String>> prereqs, Map<String, Integer> unlocks) {
        this.prereqs = prereqs;
        this.unlocks = unlocks;
    }

    static CourseWorkloadPrereqGraph load(JdbcTemplate jdbc) {
        Map<String, List<String>> prereqs = new HashMap<>();
        Map<String, Integer> unlocks = new HashMap<>();
        try {
            jdbc.query("""
                    SELECT course_id, prerequisite_course_id
                    FROM course_prerequisites
                    WHERE type = 'PREREQUISITE'
                    """, rs -> {
                String courseId = rs.getString("course_id");
                String prereqId = rs.getString("prerequisite_course_id");
                prereqs.computeIfAbsent(courseId, k -> new ArrayList<>()).add(prereqId);
                unlocks.merge(prereqId, 1, Integer::sum);
            });
        } catch (Exception ex) {
            log.debug("Skipping prerequisite graph load: {}", ex.getMessage());
        }
        return new CourseWorkloadPrereqGraph(prereqs, unlocks);
    }

    int prereqCountOf(String id) {
        return prereqs.getOrDefault(id, List.of()).size();
    }

    int unlockCountOf(String id) {
        return unlocks.getOrDefault(id, 0);
    }

    int depthOf(String id) {
        return depthOf(id, new HashSet<>());
    }

    private int depthOf(String id, Set<String> visiting) {
        if (depthCache.containsKey(id)) {
            return depthCache.get(id);
        }
        if (!visiting.add(id)) {
            return 0;
        }
        int max = 0;
        for (String prereqId : prereqs.getOrDefault(id, List.of())) {
            max = Math.max(max, 1 + depthOf(prereqId, visiting));
        }
        visiting.remove(id);
        depthCache.put(id, max);
        return max;
    }
}
