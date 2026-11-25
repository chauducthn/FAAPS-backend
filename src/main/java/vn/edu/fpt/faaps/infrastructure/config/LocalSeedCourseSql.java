package vn.edu.fpt.faaps.infrastructure.config;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LocalSeedCourseSql {

    private final JdbcTemplate jdbc;

    LocalSeedCourseSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String ensureCategory(String code, String name, String description) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "course_categories") < 0) {
            return UUID.randomUUID().toString();
        }
        List<String> existing = jdbc.query("SELECT id FROM course_categories WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO course_categories (id, code, name, description) VALUES (?, ?, ?, ?)",
                    id, code, name, description);
        } catch (Exception ex) {
            jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, ?, ?)", id, code, name);
        }
        return id;
    }

    String ensureCourse(String code, String name, int credits, String categoryId,
            String description, String materialLink) {
        List<String> existing = jdbc.query("SELECT id FROM courses WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO courses (id, code, name, credits, category_id, description, material_link, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, code, name, credits, categoryId, description, materialLink);
        } catch (Exception ex) {
            jdbc.update("""
                    INSERT INTO courses (id, code, name, credits, category_id, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, code, name, credits, categoryId);
        }
        return id;
    }

    void ensurePrerequisite(String courseId, String prereqCourseId) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "course_prerequisites") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_prerequisites WHERE course_id = ? AND prerequisite_course_id = ?",
                Integer.class, courseId, prereqCourseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type)
                    VALUES (?, ?, ?, 1, 'PREREQUISITE')
                    """, UUID.randomUUID().toString(), courseId, prereqCourseId);
        }
    }

    void ensureCurriculumCourse(String curriculumVersionId, String courseId, int semesterNumber) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "curriculum_courses") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ? AND course_id = ?",
                Integer.class, curriculumVersionId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory)
                    VALUES (?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), curriculumVersionId, courseId, semesterNumber);
        }
    }

    void ensureAcademicRule(String curriculumVersionId, String policyType, String conditionExpr,
            String restrictionExpr, boolean approvalRequired, int priority, String description) {
        if (LocalDatabaseSeeder.safeCount(jdbc, "academic_policies") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic_policies WHERE policy_type = ?", Integer.class, policyType);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO academic_policies (
                        id, curriculum_version_id, policy_type, condition_expression,
                        restriction_expression, approval_required, priority, description, is_active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), curriculumVersionId, policyType,
                    conditionExpr, restrictionExpr, approvalRequired, priority, description);
        }
    }
}
