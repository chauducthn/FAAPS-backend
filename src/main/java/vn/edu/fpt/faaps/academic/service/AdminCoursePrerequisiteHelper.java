package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.Conflict;
import vn.edu.fpt.faaps.academic.service.AdminCourseService.PrerequisiteInput;

final class AdminCoursePrerequisiteHelper {
    private final JdbcTemplate jdbc;
    private final CourseRelationService relationService;

    AdminCoursePrerequisiteHelper(JdbcTemplate jdbc, CourseRelationService relationService) {
        this.jdbc = jdbc;
        this.relationService = relationService;
    }

    void validate(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites == null) {
            return;
        }
        for (PrerequisiteInput relation : prerequisites) {
            if (relation == null || relation.prerequisiteCourseId() == null
                    || relation.prerequisiteCourseId().equals(courseId)) {
                throw new IllegalArgumentException("Related course must be different from this course");
            }
            String type = relation.type() == null || relation.type().isBlank()
                    ? "PREREQUISITE" : relation.type().strip().toUpperCase();
            if (!List.of("PREREQUISITE", "REPLACEMENT").contains(type)) {
                throw new IllegalArgumentException("Unsupported course relation type: " + type);
            }
            if (relationService.createsCycle(courseId, relation.prerequisiteCourseId())) {
                throw new Conflict("Circular course prerequisite detected");
            }
        }
    }

    void save(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites == null || prerequisites.isEmpty()) {
            return;
        }
        for (PrerequisiteInput p : prerequisites) {
            String type = (p.type() != null && !p.type().isBlank()) ? p.type().strip().toUpperCase() : "PREREQUISITE";
            int group = p.groupNo() != null && p.groupNo() > 0 ? p.groupNo() : 1;
            BigDecimal minScore = type.equals("REPLACEMENT") ? null
                    : p.minimumScore() != null ? p.minimumScore() : new BigDecimal("5.00");
            try {
                jdbc.update("""
                        INSERT INTO course_prerequisites
                            (id, course_id, prerequisite_course_id, group_no, type, minimum_score, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID().toString(), courseId.toString(),
                        p.prerequisiteCourseId().toString(), group, type, minScore);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Could not save course relation", exception);
            }
        }
    }

    void replace(UUID courseId, List<PrerequisiteInput> prerequisites) {
        if (prerequisites != null) {
            jdbc.update("DELETE FROM course_prerequisites WHERE course_id = ?", courseId.toString());
            save(courseId, prerequisites);
        }
    }
}
