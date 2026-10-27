package vn.edu.fpt.faaps.academic.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.DecisionRequest;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Detail;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Page;
import vn.edu.fpt.faaps.academic.service.AdvisorStudyPlanService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping("/api/advisor/study-plans")
public class AdvisorStudyPlanController {
    private final AdvisorStudyPlanService plans;
    public AdvisorStudyPlanController(AdvisorStudyPlanService plans) { this.plans = plans; }

    @GetMapping
    public Page list(@AuthenticationPrincipal AuthSession advisor,
            @RequestParam(defaultValue = "SUBMITTED") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return plans.list(advisor.id(), status, page, size);
    }

    @GetMapping("/{id}")
    public Detail detail(@AuthenticationPrincipal AuthSession advisor, @PathVariable UUID id) {
        return plans.detail(advisor.id(), id);
    }

    @PostMapping("/{id}/decision")
    public Detail decide(@AuthenticationPrincipal AuthSession advisor, @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest request) {
        return plans.decide(advisor.id(), id, request.decision(), request.comment());
    }
}
