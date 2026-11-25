package vn.edu.fpt.faaps.ai.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

class StudentRoadmapService {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String DEFAULT_DESC =
            "Optimal curriculum progression based on prerequisites and university guidelines.";

    private final StudentRoadmapStore store;

    StudentRoadmapService(JdbcTemplate jdbc) {
        this.store = new StudentRoadmapStore(jdbc);
    }

    StudyRoadmapDto get(UUID studentId) {
        return store.findCurrent(studentId);
    }

    StudyRoadmapDto generate(UUID studentId, GenerateRoadmapDto input) {
        validateInput(input);
        store.updateExpectedGraduationIfProvided(studentId, input);
        List<Map<String, Object>> courses = store.curriculumCourses(studentId);
        int maxCredits = input != null && input.maxCreditsPerSemester() != null
                && input.maxCreditsPerSemester() > 0 ? input.maxCreditsPerSemester() : 20;
        Map<Integer, List<RoadmapCourseDto>> grouped = groupCourses(courses, maxCredits, input);
        List<RoadmapSemesterDto> semesters = semesters(grouped, maxCredits);
        int totalCredits = semesters.stream().mapToInt(RoadmapSemesterDto::totalCredits).sum();
        String planId = UUID.randomUUID().toString();
        store.save(studentId, planId, semesters, totalCredits);
        String description = buildDescription(input);
        return new StudyRoadmapDto(planId, studentId.toString(), "Curriculum Study Roadmap",
                description, LocalDateTime.now().format(ISO_DT), "RULE_BASED", null,
                semesters.size(), totalCredits, semesters);
    }

    private void validateInput(GenerateRoadmapDto input) {
        if (input == null) {
            return;
        }
        if (input.maxCreditsPerSemester() != null
                && (input.maxCreditsPerSemester() < 1 || input.maxCreditsPerSemester() > 30)) {
            throw new IllegalArgumentException("maxCreditsPerSemester must be between 1 and 30");
        }
        if (input.targetGpa() != null && (input.targetGpa() < 0.0 || input.targetGpa() > 10.0)) {
            throw new IllegalArgumentException("targetGpa must be between 0.00 and 10.00");
        }
        if (input.orientation() != null && !input.orientation().isBlank()) {
            String orient = input.orientation().strip().toUpperCase();
            if (!Set.of("ACADEMIC", "JOB", "RESEARCH", "ENTREPRENEURIAL").contains(orient)) {
                throw new IllegalArgumentException("orientation must be ACADEMIC, JOB, RESEARCH, or ENTREPRENEURIAL");
            }
        }
    }

    private String buildDescription(GenerateRoadmapDto input) {
        if (input == null) {
            return DEFAULT_DESC;
        }
        List<String> parts = new ArrayList<>();
        if (input.goal() != null && !input.goal().isBlank()) {
            parts.add("Customized for goal: " + input.goal().strip());
        }
        if (input.orientation() != null && !input.orientation().isBlank()) {
            parts.add("Orientation: " + input.orientation().strip().toUpperCase());
        }
        if (input.targetGpa() != null) {
            parts.add(String.format(Locale.ROOT, "Target GPA: %.2f", input.targetGpa()));
        }
        if (input.expectedGraduationSemesterId() != null && !input.expectedGraduationSemesterId().isBlank()) {
            String expId = input.expectedGraduationSemesterId().strip();
            String semCode = store.resolveSemesterCode(expId);
            parts.add("Target graduation: " + (semCode != null ? semCode : expId));
        }
        return parts.isEmpty() ? DEFAULT_DESC : String.join(" | ", parts);
    }

    private Map<Integer, List<RoadmapCourseDto>> groupCourses(List<Map<String, Object>> rows, int maxCredits,
            GenerateRoadmapDto input) {
        Map<Integer, List<RoadmapCourseDto>> grouped = new HashMap<>();
        int semester = 1;
        int creditsInSemester = 0;
        String orientSuffix = input != null && input.orientation() != null && !input.orientation().isBlank()
                ? " (" + input.orientation().strip().toUpperCase() + " track)"
                : "";
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
                    ? "Mandatory core curriculum course required for degree completion" + orientSuffix + "."
                    : "Recommended elective to satisfy degree specialization requirements" + orientSuffix + ".";
            var course = new RoadmapCourseDto(courseId, (String) row.get("code"), (String) row.get("name"),
                    credits, (String) row.get("category_name"), mandatory, mandatory ? "HIGH" : "MEDIUM",
                    semester == 1 ? "IN_PROGRESS" : "PLANNED", reason, store.prerequisites(courseId));
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

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : !(value instanceof Number number) || number.intValue() == 1;
    }
}
