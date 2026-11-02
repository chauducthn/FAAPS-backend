package vn.edu.fpt.faaps.advising.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.Profile;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.UpdateRequest;
import vn.edu.fpt.faaps.advising.service.AdvisorProfileService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping("/api/advisor/profile")
public class AdvisorProfileController {
    private final AdvisorProfileService profiles;
    public AdvisorProfileController(AdvisorProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public Profile get(@AuthenticationPrincipal AuthSession advisor) { return profiles.get(advisor.id()); }

    @PutMapping
    public Profile update(@AuthenticationPrincipal AuthSession advisor,
            @Valid @RequestBody UpdateRequest request) {
        return profiles.update(advisor.id(), request.fullName(), request.phone(), request.specialization());
    }
}
