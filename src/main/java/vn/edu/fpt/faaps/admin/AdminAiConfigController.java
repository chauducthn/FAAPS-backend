package vn.edu.fpt.faaps.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.AiConfigDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.AiConfigSecretDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.AiPerformanceResultDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.AiPresetDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.UpdateAiConfigDto;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping({"/api/admin/ai-configurations", "/admin/ai-configurations"})
public class AdminAiConfigController {

    private final AdminAiConfigService service;

    public AdminAiConfigController(AdminAiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<AiConfigDto> list(
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String isActive) {
        return service.list(purpose, provider, isActive);
    }

    @GetMapping("/presets")
    public List<AiPresetDto> getPresets() {
        return service.getPresets();
    }

    @GetMapping("/{id}")
    public AiConfigDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiConfigDto create(
            @Valid @RequestBody CreateAiConfigDto dto,
            @AuthenticationPrincipal AuthSession actor) {
        return service.create(dto, actor != null ? actor.id() : null);
    }

    @PutMapping("/{id}")
    public AiConfigDto update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAiConfigDto dto,
            @AuthenticationPrincipal AuthSession actor) {
        return service.update(id, dto, actor != null ? actor.id() : null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/secret")
    public ResponseEntity<Void> setSecret(
            @PathVariable UUID id,
            @Valid @RequestBody AiConfigSecretDto secretDto,
            @AuthenticationPrincipal AuthSession actor) {
        service.setSecret(id, secretDto, actor != null ? actor.id() : null);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/performance")
    public AiPerformanceResultDto getPerformanceFromConfigRoute(
            @RequestParam(required = false) UUID configId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return service.getPerformance(configId, purpose, startDate, endDate);
    }
}
