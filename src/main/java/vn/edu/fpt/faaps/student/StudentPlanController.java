package vn.edu.fpt.faaps.student;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping("/api/student")
public class StudentPlanController {
    private final StudentPlanService plans;

    public StudentPlanController(StudentPlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/semesters")
    public List<StudentPlanService.Semester> semesters() { return plans.semesters(); }

    @GetMapping("/study-plans")
    public List<StudentPlanService.CurriculumPlan> studyPlans(@AuthenticationPrincipal AuthSession student) {
        return plans.curriculumPlans(student.id());
    }

    @PostMapping("/study-plans")
    public StudentPlanService.CurriculumPlan saveStudyPlan(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody SaveStudyPlan request) {
        return plans.saveCurriculum(student.id(), request.semesterId(), request.planType(), request.courseIds());
    }

    @GetMapping("/study-plans/{id}")
    public StudentPlanService.CurriculumPlan studyPlan(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return plans.curriculumPlan(student.id(), id); }

    @GetMapping("/study-plans/{id}/versions")
    public List<StudentPlanService.CurriculumPlan> studyPlanVersions(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return plans.curriculumVersions(student.id(), id); }

    @GetMapping("/study-plans/{id}/validate")
    public List<String> validate(@AuthenticationPrincipal AuthSession student, @PathVariable UUID id) {
        return plans.curriculumPlan(student.id(), id).issues();
    }

    @PostMapping("/study-plans/{id}/submit")
    public StudentPlanService.CurriculumPlan submit(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return plans.submitCurriculum(student.id(), id); }

    @GetMapping("/course-learning-plans")
    public List<StudentPlanService.CoursePlan> coursePlans(@AuthenticationPrincipal AuthSession student) {
        return plans.coursePlans(student.id());
    }

    @PostMapping("/course-learning-plans")
    public StudentPlanService.CoursePlan saveCoursePlan(@AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody SaveCoursePlan request) {
        return plans.saveCourse(student.id(), request.courseId(), request.learningGoal(), request.tasks());
    }

    @GetMapping("/course-learning-plans/{id}")
    public StudentPlanService.CoursePlan coursePlan(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return plans.coursePlan(student.id(), id); }

    @GetMapping("/course-learning-plans/{id}/versions")
    public List<StudentPlanService.CoursePlan> courseVersions(@AuthenticationPrincipal AuthSession student,
            @PathVariable UUID id) { return plans.courseVersions(student.id(), id); }

    public record SaveStudyPlan(@NotNull UUID semesterId, String planType, @NotEmpty List<UUID> courseIds) {}
    public record SaveCoursePlan(@NotNull UUID courseId, String learningGoal,
            @NotEmpty List<StudentPlanService.TaskInput> tasks) {}
}
