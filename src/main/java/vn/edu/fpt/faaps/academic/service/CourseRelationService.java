package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.repository.CourseReadRepository;

class CourseRelationService {
    private final JdbcTemplate jdbc;
    private final CourseReadRepository reads;

    CourseRelationService(JdbcTemplate jdbc, CourseReadRepository reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    List<AdminCourseService.CourseRelation> list(UUID courseId) {
        return reads.detail(courseId).prerequisites().stream()
                .map(r -> new AdminCourseService.CourseRelation(
                        r.id(), r.type(), r.groupNo(), r.prerequisiteCourseId(),
                        r.prerequisiteCode(), r.prerequisiteName(), r.minimumScore()))
                .toList();
    }

    @Transactional
    AdminCourseService.CourseRelation add(
            UUID courseId, String kind, Integer groupNo, UUID other, BigDecimal score) {
        reads.detail(courseId);
        validate(courseId, kind, groupNo, other, score);
        reads.detail(other);
        String type = kind.strip().toUpperCase();
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_prerequisites
                WHERE course_id = ? AND prerequisite_course_id = ? AND type = ?
                """, Integer.class, courseId.toString(), other.toString(), type);
        if (count != null && count > 0) {
            throw new AdminCourseService.Conflict("This course relation already exists");
        }
        if (createsCycle(courseId, other)) {
            throw new AdminCourseService.Conflict("Circular course prerequisite detected");
        }
        try {
            jdbc.update("""
                    INSERT INTO course_prerequisites
                        (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID().toString(),
                    courseId.toString(),
                    other.toString(),
                    type.equals("REPLACEMENT") ? 1 : groupNo,
                    type,
                    type.equals("REPLACEMENT") ? null : score);
        } catch (DuplicateKeyException e) {
            throw new AdminCourseService.Conflict("This course relation already exists");
        }
        return list(courseId).stream()
                .filter(r -> r.otherCourseId().equals(other) && r.kind().equals(type))
                .findFirst()
                .orElseThrow();
    }

    boolean createsCycle(UUID courseId, UUID candidatePrerequisiteId) {
        if (courseId.equals(candidatePrerequisiteId)) {
            return true;
        }
        String target = courseId.toString();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(candidatePrerequisiteId.toString());
        visited.add(candidatePrerequisiteId.toString());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> next = jdbc.query(
                    "SELECT prerequisite_course_id FROM course_prerequisites WHERE course_id = ?",
                    (rs, row) -> rs.getString(1), current);
            for (String dep : next) {
                if (dep == null) {
                    continue;
                }
                if (dep.equalsIgnoreCase(target)) {
                    return true;
                }
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return false;
    }

    @Transactional
    AdminCourseService.CourseRelation update(UUID courseId, UUID relationId, Integer groupNo, BigDecimal score) {
        var current = get(courseId, relationId);
        int group = current.kind().equals("REPLACEMENT") ? 1 : groupNo == null ? current.groupNo() : groupNo;
        if (group < 1 || group > 20) {
            throw new IllegalArgumentException("groupNo must be between 1 and 20");
        }
        BigDecimal saved = current.kind().equals("REPLACEMENT") ? null : score;
        validateScore(saved);
        jdbc.update("UPDATE course_prerequisites SET group_no = ?, minimum_score = ? WHERE id = ? AND course_id = ?",
                group, saved, relationId.toString(), courseId.toString());
        return get(courseId, relationId);
    }

    @Transactional
    AdminCourseService.CourseRelation delete(UUID courseId, UUID relationId) {
        var current = get(courseId, relationId);
        jdbc.update("DELETE FROM course_prerequisites WHERE id = ? AND course_id = ?",
                relationId.toString(), courseId.toString());
        return current;
    }

    private AdminCourseService.CourseRelation get(UUID courseId, UUID id) {
        return list(courseId).stream()
                .filter(r -> r.relationId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AdminCourseService.NotFound("Course relation not found"));
    }

    private void validate(UUID courseId, String kind, Integer group, UUID other, BigDecimal score) {
        if (kind == null || !List.of("PREREQUISITE", "REPLACEMENT").contains(kind.strip().toUpperCase())) {
            throw new IllegalArgumentException("kind must be PREREQUISITE or REPLACEMENT");
        }
        if (other == null || other.equals(courseId)) {
            throw new IllegalArgumentException("otherCourseId must identify a different course");
        }
        if (group == null || group < 1 || group > 20) {
            throw new IllegalArgumentException("groupNo must be between 1 and 20");
        }
        validateScore(score);
    }

    private void validateScore(BigDecimal score) {
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.TEN) > 0)) {
            throw new IllegalArgumentException("minimumScore must be between 0 and 10");
        }
    }
}
