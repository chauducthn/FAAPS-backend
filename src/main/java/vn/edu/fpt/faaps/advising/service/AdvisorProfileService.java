package vn.edu.fpt.faaps.advising.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.dto.AdvisorProfileDtos.Profile;
import vn.edu.fpt.faaps.advising.repository.AdvisorProfileRepository;
import vn.edu.fpt.faaps.common.validation.IdentityInput;

@Service
public class AdvisorProfileService {
    private final AdvisorProfileRepository repository;
    public AdvisorProfileService(AdvisorProfileRepository repository) { this.repository = repository; }

    public Profile get(UUID advisorId) {
        Profile profile = repository.find(advisorId);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Advisor profile not found");
        return profile;
    }

    @Transactional
    public Profile update(UUID advisorId, String fullName) {
        return update(advisorId, fullName, null, null);
    }

    @Transactional
    public Profile update(UUID advisorId, String fullName, String phone, String specialization) {
        String normalizedName = fullName == null ? "" : fullName.strip().replaceAll("\\s+", " ");
        if (normalizedName.isBlank()) throw new IllegalArgumentException("Full name is required");

        String normalizedPhone = IdentityInput.phone(phone);

        String normalizedSpec = specialization == null ? null : specialization.strip();
        if (normalizedSpec != null && normalizedSpec.isBlank()) {
            normalizedSpec = null;
        }

        if (repository.update(advisorId, normalizedName, normalizedPhone, normalizedSpec) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Advisor profile not found");
        }
        return get(advisorId);
    }
}
