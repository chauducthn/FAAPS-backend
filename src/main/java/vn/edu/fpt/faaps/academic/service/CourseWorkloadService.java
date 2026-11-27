package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.HistoricalStats;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.WorkloadInput;

/**
 * Batch-evaluates 3-layer course workload without N+1 queries and persists manual overrides (Layer 2).
 */
@Service
public class CourseWorkloadService {
    private static final Logger log = LoggerFactory.getLogger(CourseWorkloadService.class);
    private final JdbcTemplate jdbc;
    private volatile boolean columnChecked = false;

    public CourseWorkloadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureColumn() {
        if (columnChecked) {
            return;
        }
        synchronized (this) {
            if (columnChecked) {
                return;
            }
            try {
                jdbc.execute("ALTER TABLE courses ADD COLUMN workload_level VARCHAR(20) NULL");
            } catch (Exception ex) {
                log.debug("workload_level column already exists or DDL skipped: {}", ex.getMessage());
            }
            columnChecked = true;
        }
    }

    public CourseWorkload evaluateOne(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            return null;
        }
        return evaluateByCourseIds(List.of(courseId)).get(courseId);
    }

    public Map<String, CourseWorkload> evaluateByCourseIds(Collection<String> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinctIds = courseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        List<CourseMeta> metas = loadCourseMeta(distinctIds);
        if (metas.isEmpty()) {
            return Map.of();
        }
        CourseWorkloadPrereqGraph graph = CourseWorkloadPrereqGraph.load(jdbc);
        Map<String, HistoricalStats> statsMap = loadHistoricalStats(distinctIds);

        Map<String, CourseWorkload> result = new LinkedHashMap<>(metas.size());
        for (CourseMeta m : metas) {
            WorkloadInput input = new WorkloadInput(
                    m.id(), m.code(), m.name(), m.credits(), m.categoryCode(), m.categoryName(),
                    m.knowledgeBlock(), m.gradingStructure(), m.manualLevel(),
                    graph.prereqCountOf(m.id()), graph.depthOf(m.id()), graph.unlockCountOf(m.id()));
            result.put(m.id(), CourseWorkloadEvaluator.evaluate(input, statsMap.get(m.id())));
        }
        return result;
    }

    public CourseWorkload updateManualLevel(UUID courseId, String rawLevel) {
        String normalized = CourseWorkload.normalizeManualLevel(rawLevel);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM courses WHERE id = ?", Integer.class, courseId.toString());
        if (exists == null || exists == 0) {
            throw new AdminCourseService.NotFound();
        }
        ensureColumn();
        jdbc.update("UPDATE courses SET workload_level = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                normalized, courseId.toString());
        return evaluateOne(courseId.toString());
    }

    private List<CourseMeta> loadCourseMeta(List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String from = " FROM courses c LEFT JOIN course_categories cat ON cat.id = c.category_id WHERE c.id IN ("
                + placeholders + ")";
        Object[] args = ids.toArray();
        try {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, cat.code AS category_code, cat.name AS category_name,
                           c.knowledge_block, c.grading_structure, c.workload_level
                    """ + from, (rs, r) -> new CourseMeta(rs.getString("id"), rs.getString("code"),
                    rs.getString("name"), rs.getInt("credits"), rs.getString("category_code"),
                    rs.getString("category_name"), rs.getString("knowledge_block"),
                    rs.getString("grading_structure"), rs.getString("workload_level")), args);
        } catch (Exception ex) {
            return loadCourseMetaFallback(from, args);
        }
    }

    private List<CourseMeta> loadCourseMetaFallback(String from, Object[] args) {
        try {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, cat.code AS category_code, cat.name AS category_name,
                           c.knowledge_block, c.grading_structure
                    """ + from, (rs, r) -> new CourseMeta(rs.getString("id"), rs.getString("code"),
                    rs.getString("name"), rs.getInt("credits"), rs.getString("category_code"),
                    rs.getString("category_name"), rs.getString("knowledge_block"),
                    rs.getString("grading_structure"), null), args);
        } catch (Exception ex) {
            return loadCourseMetaMinimalFallback(from, args);
        }
    }

    private List<CourseMeta> loadCourseMetaMinimalFallback(String from, Object[] args) {
        try {
            return jdbc.query("""
                    SELECT c.id, c.code, c.name, c.credits, cat.code AS category_code, cat.name AS category_name,
                           c.workload_level
                    """ + from, (rs, r) -> new CourseMeta(rs.getString("id"), rs.getString("code"),
                    rs.getString("name"), rs.getInt("credits"), rs.getString("category_code"),
                    rs.getString("category_name"), null, null, rs.getString("workload_level")), args);
        } catch (Exception ex) {
            try {
                return jdbc.query("""
                        SELECT c.id, c.code, c.name, c.credits, cat.code AS category_code, cat.name AS category_name
                        """ + from, (rs, r) -> new CourseMeta(rs.getString("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("credits"), rs.getString("category_code"),
                        rs.getString("category_name"), null, null, null), args);
            } catch (Exception ex2) {
                return jdbc.query("""
                        SELECT c.id, c.code, c.name, c.credits, NULL AS category_code, cat.name AS category_name
                        """ + from, (rs, r) -> new CourseMeta(rs.getString("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("credits"), null,
                        rs.getString("category_name"), null, null, null), args);
            }
        }
    }

    private Map<String, HistoricalStats> loadHistoricalStats(List<String> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, HistoricalStats> map = new HashMap<>();
        try {
            jdbc.query("""
                    SELECT course_id,
                           COUNT(*) AS sample_size,
                           SUM(CASE WHEN UPPER(status) = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                           AVG(final_score) AS avg_score
                    FROM student_course_records
                    WHERE UPPER(status) IN ('PASSED', 'FAILED')
                      AND course_id IN (""" + placeholders + ") GROUP BY course_id", rs -> {
                String cid = rs.getString("course_id");
                int sample = rs.getInt("sample_size");
                int failed = rs.getInt("failed_count");
                BigDecimal avg = rs.getBigDecimal("avg_score");
                map.put(cid, HistoricalStats.fromCounts(sample, failed, avg));
            }, ids.toArray());
        } catch (Exception ex) {
            log.debug("Skipping historical stats load: {}", ex.getMessage());
        }
        return map;
    }

    private record CourseMeta(String id, String code, String name, int credits, String categoryCode,
            String categoryName, String knowledgeBlock, String gradingStructure, String manualLevel) {}
}
