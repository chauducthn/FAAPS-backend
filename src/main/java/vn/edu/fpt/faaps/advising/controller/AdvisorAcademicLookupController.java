package vn.edu.fpt.faaps.advising.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.common.dto.ApiError;

@RestController
@RequestMapping({"/api/advisor/academic", "/advisor/academic"})
public class AdvisorAcademicLookupController {
    private final AdvisorAcademicLookupQueries queries;

    public AdvisorAcademicLookupController(JdbcTemplate jdbc) {
        this.queries = new AdvisorAcademicLookupQueries(jdbc);
    }

    @GetMapping("/courses")
    public List<LookupCourse> getCourses(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) {
        return queries.findCourses(search, category);
    }

    @GetMapping("/courses/{id}")
    public LookupCourse getCourseDetail(@PathVariable UUID id) {
        return queries.findCourseDetail(id);
    }

    @GetMapping("/curriculums")
    public List<LookupCurriculum> getCurriculums() {
        return queries.findCurriculums();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        String code = ex.getStatusCode().value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        return ResponseEntity.status(ex.getStatusCode()).body(ApiError.of(code, ex.getReason()));
    }

    public record LookupPrerequisite(String courseCode, String courseName, int groupNo, BigDecimal minimumScore) {}

    public record SyllabusWeek(int week, String topic, String content) {}

    public record LookupCourse(
            String id, String code, String name, int credits, String category,
            String description, String objectives, String learningOutcomes, String materialLink,
            List<LookupPrerequisite> prerequisites, List<SyllabusWeek> syllabus) {
        public LookupCourse(
                String id, String code, String name, int credits, String category,
                String description, String objectives, String learningOutcomes,
                List<LookupPrerequisite> prerequisites, List<SyllabusWeek> syllabus) {
            this(id, code, name, credits, category, description, objectives, learningOutcomes,
                    null, prerequisites, syllabus);
        }
    }

    public record LookupSemester(int semesterNumber, List<String> courses) {}

    public record LookupCurriculum(
            String id, String majorCode, String majorName, String version,
            String name, String description, int totalCredits, List<LookupSemester> semesters) {}
}
