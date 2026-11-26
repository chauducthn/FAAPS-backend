package vn.edu.fpt.faaps.ai.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

final class StudentRoadmapStore {

    private static final Logger log = LoggerFactory.getLogger(StudentRoadmapStore.class);
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;

    StudentRoadmapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    StudyRoadmapDto findCurrent(UUID studentId) {
        return jdbc.query("""
                SELECT id, created_at FROM study_plans
                WHERE student_id = ? AND is_current = TRUE
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> build(rs.getString("id"), studentId, timestamp(rs.getTimestamp("created_at"))),
                studentId.toString()).stream().findFirst().orElse(null);
    }

    void updateExpectedGraduationIfProvided(UUID studentId, GenerateRoadmapDto input) {
        if (input == null || input.expectedGraduationSemesterId() == null
                || input.expectedGraduationSemesterId().isBlank()) {
            return;
        }
        String expSemId = input.expectedGraduationSemesterId().strip();
        try {
            List<String> exists = jdbc.query("SELECT id FROM semesters WHERE id = ?",
                    (rs, row) -> rs.getString(1), expSemId);
            if (!exists.isEmpty()) {
                jdbc.update("""
                        UPDATE students
                        SET expected_graduation_semester_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ?
                        """, expSemId, studentId.toString());
            }
        } catch (Exception ex) {
            log.debug("Could not update expected graduation semester for student {}: {}", studentId, ex.getMessage());
        }
    }

    String resolveSemesterCode(String semesterId) {
        try {
            return jdbc.query("SELECT code FROM semesters WHERE id = ?", (rs, row) -> rs.getString(1), semesterId)
                    .stream().findFirst().orElse(null);
        } catch (Exception ex) {
            log.debug("Could not resolve semester code for {}: {}", semesterId, ex.getMessage());
            return null;
        }
    }

    List<Map<String, Object>> curriculumCourses(UUID studentId) {
        Set<String> passedIds = passedCourseIds(studentId);
        String curriculumId = jdbc.queryForList(
                "SELECT curriculum_version_id FROM students WHERE user_id = ?", studentId.toString())
                .stream().map(row -> row.get("curriculum_version_id")).filter(Objects::nonNull)
                .map(value -> value.toString()).findFirst().orElse(null);
        List<Map<String, Object>> courses = curriculumId == null ? List.of() : jdbc.queryForList("""
                SELECT c.id, c.code, c.name, c.credits, cat.name AS category_name,
                       cc.semester_number, cc.is_mandatory
                FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id
                LEFT JOIN course_categories cat ON cat.id = c.category_id
                WHERE cc.curriculum_version_id = ? ORDER BY cc.semester_number, c.code
                """, curriculumId);
        if (courses.isEmpty()) {
            courses = jdbc.queryForList("""
                    SELECT c.id, c.code, c.name, c.credits, cat.name AS category_name,
                           1 AS semester_number, 1 AS is_mandatory
                    FROM courses c LEFT JOIN course_categories cat ON cat.id = c.category_id LIMIT 20
                    """);
        }
        if (passedIds.isEmpty()) {
            return courses;
        }
        return courses.stream()
                .filter(row -> row.get("id") == null || !passedIds.contains(row.get("id").toString()))
                .toList();
    }

    List<String> prerequisites(String courseId) {
        return jdbc.query("""
                SELECT p.code FROM course_prerequisites cp
                JOIN courses p ON p.id = cp.prerequisite_course_id WHERE cp.course_id = ?
                """, (rs, row) -> rs.getString(1), courseId);
    }

    void save(UUID studentId, String planId, List<RoadmapSemesterDto> semesters, int totalCredits) {
        String semesterId = resolveCurrentSemesterId();
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1 FROM study_plans
                WHERE student_id = ? AND semester_id = ?
                """, Integer.class, studentId.toString(), semesterId);
        int version = nextVersion == null ? 1 : nextVersion;
        jdbc.update("UPDATE study_plans SET is_current = FALSE WHERE student_id = ?", studentId.toString());
        jdbc.update("""
                INSERT INTO study_plans (id, student_id, semester_id, version, is_current, plan_type,
                    total_credits_planned, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'REGULAR', ?, 'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, planId, studentId.toString(), semesterId, version, totalCredits);
        for (RoadmapSemesterDto semester : semesters) {
            for (RoadmapCourseDto course : semester.courses()) {
                jdbc.update("""
                        INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority, notes)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), planId, course.courseId(), course.credits(),
                        course.priority(), course.reason());
                jdbc.update("""
                        INSERT INTO course_recommendations (id, student_id, course_id, semester_id,
                            recommendation_source, confidence_score, reasoning, priority, status)
                        VALUES (?, ?, ?, ?, 'CURRICULUM', NULL, ?, ?, 'PENDING')
                        """, UUID.randomUUID().toString(), studentId.toString(), course.courseId(),
                        semesterId, course.reason(), course.priority());
            }
        }
    }

    private Set<String> passedCourseIds(UUID studentId) {
        try {
            return new HashSet<>(jdbc.query("""
                    SELECT DISTINCT course_id FROM student_course_records
                    WHERE student_id = ? AND status IN ('PASSED', 'EXEMPTED')
                    """, (rs, row) -> rs.getString(1), studentId.toString()));
        } catch (Exception ex) {
            log.debug("Could not load passed courses for student {}: {}", studentId, ex.getMessage());
            return Set.of();
        }
    }

    private String resolveCurrentSemesterId() {
        try {
            List<String> current = jdbc.query(
                    "SELECT id FROM semesters WHERE is_current = TRUE ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!current.isEmpty()) {
                return current.get(0);
            }
        } catch (Exception ex) {
            log.debug("Current semester lookup failed, trying latest start_date: {}", ex.getMessage());
        }
        try {
            List<String> byDate = jdbc.query(
                    "SELECT id FROM semesters ORDER BY start_date DESC LIMIT 1",
                    (rs, row) -> rs.getString(1));
            if (!byDate.isEmpty()) {
                return byDate.get(0);
            }
        } catch (Exception ex) {
            log.debug("Latest semester lookup failed, falling back to any semester: {}", ex.getMessage());
        }
        return jdbc.query("SELECT id FROM semesters LIMIT 1", (rs, row) -> rs.getString(1))
                .stream().findFirst().orElse(null);
    }

    private StudyRoadmapDto build(String planId, UUID studentId, String generatedAt) {
        List<RoadmapCourseDto> courses = jdbc.query("""
                SELECT c.id, c.code, c.name, spc.planned_credits, spc.priority, spc.notes,
                       cat.name AS category_name
                FROM study_plan_courses spc JOIN courses c ON c.id = spc.course_id
                LEFT JOIN course_categories cat ON cat.id = c.category_id WHERE spc.study_plan_id = ?
                """, (rs, row) -> new RoadmapCourseDto(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getInt("planned_credits"), rs.getString("category_name"), true,
                rs.getString("priority"), "PLANNED", rs.getString("notes"), List.of()), planId);
        int credits = courses.stream().mapToInt(course -> course.credits()).sum();
        var semester = new RoadmapSemesterDto(1, "FA2026", "Semester 1", credits,
                "IN_PROGRESS", courses, "Generated plan");
        return new StudyRoadmapDto(planId, studentId.toString(), "Active Study Roadmap",
                "Based on current academic plan and graduation guidelines.", generatedAt,
                "RULE_BASED", null, 1, credits, List.of(semester));
    }

    private String timestamp(Timestamp value) {
        return (value == null ? LocalDateTime.now() : value.toLocalDateTime()).format(ISO_DT);
    }
}
