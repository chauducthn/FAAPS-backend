package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class StudentPlanPrerequisiteValidator {
    private final JdbcTemplate jdbc;

    StudentPlanPrerequisiteValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void validateCurriculumMembership(String curriculumId, List<UUID> courseIds) {
        for (UUID courseId : courseIds) {
            Integer belongs = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ? AND course_id = ?
                    """, Integer.class, curriculumId, courseId.toString());
            if (belongs == null || belongs == 0) {
                throw new IllegalArgumentException(
                        "Course is outside your curriculum: " + courseCodeOrId(courseId.toString()));
            }
        }
    }

    List<String> validateCourses(UUID studentId, List<String> courseIds) {
        List<String> issues = new ArrayList<>();
        for (String id : courseIds) {
            String courseCode = courseCodeOrId(id);
            List<Integer> groups = jdbc.query("""
                    SELECT DISTINCT group_no FROM course_prerequisites
                    WHERE course_id = ? AND type = 'PREREQUISITE'
                    ORDER BY group_no
                    """, (rs, row) -> rs.getInt(1), id);
            for (int group : groups) {
                Integer met = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM course_prerequisites p
                        JOIN student_course_records r ON r.course_id = p.prerequisite_course_id
                        WHERE p.course_id = ? AND p.group_no = ? AND p.type = 'PREREQUISITE'
                          AND r.student_id = ? AND r.status IN ('PASSED', 'EXEMPTED')
                          AND (p.minimum_score IS NULL OR r.final_score >= p.minimum_score)
                        """, Integer.class, id, group, studentId.toString());
                if (met == null || met == 0) {
                    issues.add(missingPrerequisiteMessage(id, courseCode, group));
                }
            }
        }
        return issues;
    }

    String courseCodeOrId(String courseId) {
        return jdbc.query("SELECT code FROM courses WHERE id = ?", (rs, row) -> rs.getString(1), courseId)
                .stream().findFirst().orElse(courseId);
    }

    private String missingPrerequisiteMessage(String courseId, String courseCode, int group) {
        List<String> prereqCodes = jdbc.query("""
                SELECT c.code FROM course_prerequisites p
                JOIN courses c ON c.id = p.prerequisite_course_id
                WHERE p.course_id = ? AND p.group_no = ? AND p.type = 'PREREQUISITE'
                ORDER BY c.code
                """, (rs, row) -> rs.getString(1), courseId, group);
        if (prereqCodes.isEmpty()) {
            return "Prerequisite group " + group + " is not met for course " + courseCode;
        }
        return courseCode + " is missing prerequisite " + String.join(" or ", prereqCodes) + " (group " + group + ")";
    }
}
