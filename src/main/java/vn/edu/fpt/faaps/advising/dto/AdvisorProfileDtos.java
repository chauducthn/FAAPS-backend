package vn.edu.fpt.faaps.advising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public final class AdvisorProfileDtos {
    private AdvisorProfileDtos() {}
    public record Profile(UUID id, String fullName, String email, int maxAdvisees,
            int activeAdvisees, String status, LocalDate assignmentStartDate) {}
    public record UpdateRequest(@NotBlank @Size(max = 255) String fullName) {}
}
