package vn.edu.fpt.faaps.ai.controller;

import vn.edu.fpt.faaps.ai.service.AdminAiConfigService;

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
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigPageDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigSecretDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPerformanceResultDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPresetDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiTestResultDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.ApiResponse;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.UpdateAiConfigDto;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/admin/ai-configurations", "/admin/ai-configurations"})
public class AdminAiConfigController {

    private final AdminAiConfigService service;

    public AdminAiConfigController(AdminAiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AiConfigPageDto> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return ApiResponse.success(service.listPage(search, purpose, provider, isActive, page, size, sort));
    }

    @GetMapping("/presets")
    public ApiResponse<List<AiPresetDto>> getPresets() {
        return ApiResponse.success(service.getPresets());
    }

    @GetMapping("/{id}")
    public ApiResponse<AiConfigDto> get(@PathVariable UUID id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiConfigDto> create(
            @Valid @RequestBody CreateAiConfigDto dto,
            @AuthenticationPrincipal AuthSession actor) {
        return ApiResponse.success(service.create(dto, actor != null ? actor.id() : null));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiConfigDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAiConfigDto dto,
            @AuthenticationPrincipal AuthSession actor) {
        return ApiResponse.success(service.update(id, dto, actor != null ? actor.id() : null));
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

    @PostMapping("/{id}/test")
    public ApiResponse<AiTestResultDto> testConnection(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthSession actor) {
        return ApiResponse.success(service.testConnection(id, actor != null ? actor.id() : null));
    }

    @GetMapping("/performance")
    public ApiResponse<AiPerformanceResultDto> getPerformanceFromConfigRoute(
            @RequestParam(required = false) UUID configId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(service.getPerformance(configId, purpose, startDate, endDate));
    }
}


