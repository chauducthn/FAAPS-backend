package vn.edu.fpt.faaps.advising.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.Profile;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.SpecializationRequest;
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
        return profiles.update(advisor.id(), request.fullName(), request.phone(),
                request.specialization(), request.email(), request.majorGroupId());
    }

    /** Writes the advisor-specific field only; the identity fields belong to the shared /api/profile endpoint. */
    @PutMapping("/specialization")
    public Profile updateSpecialization(@AuthenticationPrincipal AuthSession advisor,
            @Valid @RequestBody SpecializationRequest request) {
        return profiles.updateSpecialization(advisor.id(), request.specialization());
    }
}
