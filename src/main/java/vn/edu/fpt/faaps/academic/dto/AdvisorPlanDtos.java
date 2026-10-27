package vn.edu.fpt.faaps.academic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.CurriculumPlan;

public final class AdvisorPlanDtos {
    private AdvisorPlanDtos() {}

    public record Summary(String id, String studentId, String studentCode, String studentName,
            String semesterCode, int version, int totalCredits, String status, LocalDateTime submittedAt) {}
    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {}
    public record Detail(String studentId, String studentCode, String studentName, CurriculumPlan plan) {}
    public record DecisionRequest(@NotBlank String decision, @Size(max = 10_000) String comment) {}
}
