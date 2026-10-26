package vn.edu.fpt.faaps.admin.controller;

import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos;
import vn.edu.fpt.faaps.admin.service.AdminAiConfigService;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiPerformanceResultDto;

@RestController
@RequestMapping({"/api/admin/ai-performance", "/admin/ai-performance"})
public class AdminAiPerformanceController {

    private final AdminAiConfigService service;

    public AdminAiPerformanceController(AdminAiConfigService service) {
        this.service = service;
    }

    @GetMapping
    public AiPerformanceResultDto getPerformance(
            @RequestParam(required = false) UUID configId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return service.getPerformance(configId, purpose, startDate, endDate);
    }
}
