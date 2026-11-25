package vn.edu.fpt.faaps.advising.controller;

import vn.edu.fpt.faaps.advising.service.AdvisorStudentService;

import java.math.BigDecimal;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos;
import vn.edu.fpt.faaps.academic.service.AdvisorStudyPlanService;
import vn.edu.fpt.faaps.academic.service.StudentAcademicService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping({"/api/advisor/students", "/advisor/students"})
public class AdvisorStudentController {
    private final AdvisorStudentService students;
    private final StudentAcademicService academics;
    private final AdvisorStudyPlanService studyPlans;

    public AdvisorStudentController(AdvisorStudentService students, StudentAcademicService academics) {
        this(students, academics, null);
    }

    @Autowired
    public AdvisorStudentController(AdvisorStudentService students, StudentAcademicService academics,
            AdvisorStudyPlanService studyPlans) {
        this.students = students;
        this.academics = academics;
        this.studyPlans = studyPlans;
    }

    @GetMapping
    public AdvisorStudentService.Page list(@AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return students.list(advisor.id(), search, status, page, size);
    }

    @GetMapping("/{studentId}")
    public Detail detail(@AuthenticationPrincipal AuthSession advisor, @PathVariable UUID studentId) {
        if (!students.owns(advisor.id(), studentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        return new Detail(academics.profile(studentId), academics.progress(studentId), academics.transcript(studentId));
    }

    @GetMapping("/{studentId}/study-plans")
    public AdvisorPlanDtos.Page studentStudyPlans(
            @AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID studentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!students.owns(advisor.id(), studentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        if (studyPlans == null) {
            return new AdvisorPlanDtos.Page(List.of(), page, size, 0, 0);
        }
        return studyPlans.listByStudent(advisor.id(), studentId, page, size);
    }

    @GetMapping("/{studentId}/graduation-check")
    public GraduationCheck graduationCheck(
            @AuthenticationPrincipal AuthSession advisor,
            @PathVariable UUID studentId) {
        if (!students.owns(advisor.id(), studentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        StudentAcademicService.Profile profile = academics.profile(studentId);
        StudentAcademicService.Progress progress = academics.progress(studentId);
        List<StudentAcademicService.TranscriptRow> transcript = academics.transcript(studentId);

        int requiredCredits = progress.requiredCredits();
        int earnedCredits = progress.earnedCredits();
        BigDecimal gpa = profile.currentGpa() != null ? profile.currentGpa() : BigDecimal.ZERO;

        String ojtStatus = "NOT_STARTED";
        for (StudentAcademicService.TranscriptRow row : transcript) {
            if (row.courseCode() != null && row.courseCode().toUpperCase().startsWith("OJT")) {
                if ("PASSED".equalsIgnoreCase(row.status()) || "EXEMPTED".equalsIgnoreCase(row.status())) {
                    ojtStatus = "COMPLETED";
                    break;
                } else if ("IN_PROGRESS".equalsIgnoreCase(row.status())) {
                    ojtStatus = "IN_PROGRESS";
                }
            }
        }

        List<String> unmet = new ArrayList<>();
        if (requiredCredits > 0 && earnedCredits < requiredCredits) {
            unmet.add("Missing " + (requiredCredits - earnedCredits) + " required credit(s)");
        }
        if (gpa.compareTo(new BigDecimal("5.00")) < 0) {
            unmet.add("Cumulative GPA below 5.00");
        }
        if (!"COMPLETED".equals(ojtStatus)) {
            unmet.add("On-Job Training (OJT) is not completed");
        }
        if ("PROBATION".equalsIgnoreCase(profile.academicStatus())
                || "SUSPENDED".equalsIgnoreCase(profile.academicStatus())) {
            unmet.add("Academic standing is " + profile.academicStatus());
        }

        boolean eligible = requiredCredits > 0 && earnedCredits >= requiredCredits
                && gpa.compareTo(new BigDecimal("5.00")) >= 0
                && "COMPLETED".equals(ojtStatus)
                && unmet.isEmpty();

        return new GraduationCheck(eligible, requiredCredits, earnedCredits, ojtStatus, unmet);
    }

    public record Detail(StudentAcademicService.Profile profile, StudentAcademicService.Progress progress,
            List<StudentAcademicService.TranscriptRow> transcript) {}
    public record GraduationCheck(boolean eligible, int requiredCredits, int earnedCredits,
            String ojtStatus, List<String> unmetPrerequisites) {}
}


