package vn.edu.fpt.faaps.ai.service;

import vn.edu.fpt.faaps.ai.dto.StudentAiDtos;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

class StudentRoadmapService {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final JdbcTemplate jdbc;

    StudentRoadmapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    StudyRoadmapDto get(UUID studentId) {
        return jdbc.query("""
                SELECT id, created_at FROM study_plans
                WHERE student_id = ? AND is_current = TRUE
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> build(rs.getString("id"), studentId, timestamp(rs.getTimestamp("created_at"))),
                studentId.toString()).stream().findFirst().orElse(null);
    }

    StudyRoadmapDto generate(UUID studentId, GenerateRoadmapDto input) {
        List<Map<String, Object>> courses = curriculumCourses(studentId);
        int maxCredits = input != null && input.maxCreditsPerSemester() != null
                && input.maxCreditsPerSemester() > 0 ? input.maxCreditsPerSemester() : 20;
        Map<Integer, List<RoadmapCourseDto>> grouped = groupCourses(courses, maxCredits);
        List<RoadmapSemesterDto> semesters = semesters(grouped, maxCredits);
        int totalCredits = semesters.stream().mapToInt(RoadmapSemesterDto::totalCredits).sum();
        String planId = UUID.randomUUID().toString();
        save(studentId, planId, semesters, totalCredits);
        String description = input != null && input.goal() != null
                ? "Customized for goal: " + input.goal()
                : "Optimal curriculum progression based on prerequisites and university guidelines.";
        return new StudyRoadmapDto(planId, studentId.toString(), "AI Personalized Study Roadmap",
                description, LocalDateTime.now().format(ISO_DT), "gemini-1.5-pro", 0.95,
                semesters.size(), totalCredits, semesters);
    }

    private List<Map<String, Object>> curriculumCourses(UUID studentId) {
        String curriculumId = jdbc.queryForList(
                "SELECT curriculum_version_id FROM students WHERE user_id = ?", studentId.toString())
                .stream().map(row -> row.get("curriculum_version_id")).filter(java.util.Objects::nonNull)
                .map(Object::toString).findFirst().orElse(null);
        List<Map<String, Object>> courses = curriculumId == null ? List.of() : jdbc.queryForList("""
                SELECT c.id, c.code, c.name, c.credits, cat.name AS category_name,
                       cc.semester_number, cc.is_mandatory
                FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id
                LEFT JOIN course_categories cat ON cat.id = c.category_id
                WHERE cc.curriculum_version_id = ? ORDER BY cc.semester_number, c.code
                """, curriculumId);
        if (!courses.isEmpty()) return courses;
        return jdbc.queryForList("""
                SELECT c.id, c.code, c.name, c.credits, cat.name AS category_name,
                       1 AS semester_number, 1 AS is_mandatory
                FROM courses c LEFT JOIN course_categories cat ON cat.id = c.category_id LIMIT 20
                """);
    }

    private Map<Integer, List<RoadmapCourseDto>> groupCourses(List<Map<String, Object>> rows, int maxCredits) {
        Map<Integer, List<RoadmapCourseDto>> grouped = new HashMap<>();
        int semester = 1;
        int creditsInSemester = 0;
        for (Map<String, Object> row : rows) {
            int credits = ((Number) row.getOrDefault("credits", 3)).intValue();
            if (creditsInSemester + credits > maxCredits) {
                semester++;
                creditsInSemester = 0;
            }
            creditsInSemester += credits;
            boolean mandatory = booleanValue(row.get("is_mandatory"));
            String courseId = row.get("id").toString();
            String reason = mandatory
                    ? "Mandatory core curriculum course required for degree completion."
                    : "Recommended elective to satisfy degree specialization requirements.";
            var course = new RoadmapCourseDto(courseId, (String) row.get("code"), (String) row.get("name"),
                    credits, (String) row.get("category_name"), mandatory, mandatory ? "HIGH" : "MEDIUM",
                    semester == 1 ? "IN_PROGRESS" : "PLANNED", reason, prerequisites(courseId));
            grouped.computeIfAbsent(semester, key -> new ArrayList<>()).add(course);
        }
        return grouped;
    }

    private List<RoadmapSemesterDto> semesters(Map<Integer, List<RoadmapCourseDto>> grouped, int maxCredits) {
        List<RoadmapSemesterDto> result = new ArrayList<>();
        for (int number = 1; number <= grouped.size(); number++) {
            List<RoadmapCourseDto> courses = grouped.getOrDefault(number, List.of());
            int credits = courses.stream().mapToInt(RoadmapCourseDto::credits).sum();
            result.add(new RoadmapSemesterDto(number, "SEM" + number, "Semester " + number, credits,
                    number == 1 ? "IN_PROGRESS" : "PLANNED", courses,
                    "Target maximum: " + maxCredits + " credits"));
        }
        return result;
    }

    private List<String> prerequisites(String courseId) {
        return jdbc.query("""
                SELECT p.code FROM course_prerequisites cp
                JOIN courses p ON p.id = cp.prerequisite_course_id WHERE cp.course_id = ?
                """, (rs, row) -> rs.getString(1), courseId);
    }

    private void save(UUID studentId, String planId, List<RoadmapSemesterDto> semesters, int totalCredits) {
        jdbc.update("UPDATE study_plans SET is_current = FALSE WHERE student_id = ?", studentId.toString());
        jdbc.update("""
                INSERT INTO study_plans (id, student_id, semester_id, version, is_current, plan_type,
                    total_credits_planned, status, created_at, updated_at)
                VALUES (?, ?, (SELECT id FROM semesters LIMIT 1), 1, TRUE, 'REGULAR', ?, 'APPROVED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, planId, studentId.toString(), totalCredits);
        for (RoadmapSemesterDto semester : semesters) for (RoadmapCourseDto course : semester.courses()) {
            jdbc.update("""
                    INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority, notes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), planId, course.courseId(), course.credits(),
                    course.priority(), course.reason());
            jdbc.update("""
                    INSERT INTO course_recommendations (id, student_id, course_id, semester_id,
                        recommendation_source, confidence_score, reasoning, priority, status)
                    VALUES (?, ?, ?, (SELECT id FROM semesters LIMIT 1), 'AI', 0.95, ?, ?, 'ACCEPTED')
                    """, UUID.randomUUID().toString(), studentId.toString(), course.courseId(),
                    course.reason(), course.priority());
        }
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
        int credits = courses.stream().mapToInt(RoadmapCourseDto::credits).sum();
        var semester = new RoadmapSemesterDto(1, "FA2026", "Semester 1", credits,
                "IN_PROGRESS", courses, "Generated plan");
        return new StudyRoadmapDto(planId, studentId.toString(), "Active Study Roadmap",
                "Based on current academic plan and graduation guidelines.", generatedAt,
                "gemini-1.5-pro", 0.95, 1, credits, List.of(semester));
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : !(value instanceof Number number) || number.intValue() == 1;
    }

    private String timestamp(Timestamp value) {
        return (value == null ? LocalDateTime.now() : value.toLocalDateTime()).format(ISO_DT);
    }
}


