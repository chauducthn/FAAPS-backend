package vn.edu.fpt.faaps.advising.controller;

import vn.edu.fpt.faaps.advising.service.CohortAdvisorService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/assignments/cohorts/{cohortId}/advisors")
public class CohortAdvisorController {
    private final CohortAdvisorService cohorts;

    public CohortAdvisorController(CohortAdvisorService cohorts) {
        this.cohorts = cohorts;
    }

    @GetMapping
    public List<CohortAdvisorService.CohortAdvisor> list(@PathVariable UUID cohortId) {
        return cohorts.list(cohortId);
    }

    @PutMapping
    public List<CohortAdvisorService.CohortAdvisor> replace(@PathVariable UUID cohortId,
            @Valid @RequestBody AdvisorsRequest request) {
        return cohorts.replace(cohortId, request.advisorIds());
    }

    public record AdvisorsRequest(@NotNull List<UUID> advisorIds) {}
}


