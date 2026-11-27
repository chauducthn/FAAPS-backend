package vn.edu.fpt.faaps.academic.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.AcademicBlockConfigService;
import vn.edu.fpt.faaps.academic.service.AcademicBlockConfigService.BlockInput;
import vn.edu.fpt.faaps.academic.service.AcademicBlockConfigService.BlockItem;
import vn.edu.fpt.faaps.academic.service.AcademicBlockConfigService.ConfigView;
import vn.edu.fpt.faaps.academic.service.AcademicBlockConfigService.SemesterLimits;

@RestController
public class AcademicBlockConfigController {
    private final AcademicBlockConfigService service;

    public AcademicBlockConfigController(AcademicBlockConfigService service) {
        this.service = service;
    }

    @GetMapping({"/api/admin/academic-blocks", "/api/student/academic-blocks", "/student/academic-blocks"})
    public ConfigView getConfig() {
        return service.getConfig();
    }

    @PutMapping("/api/admin/academic-blocks/limits")
    public SemesterLimits updateLimits(@RequestBody SemesterLimits input) {
        return service.updateLimits(input);
    }

    @PostMapping("/api/admin/academic-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockItem createBlock(@RequestBody BlockInput input) {
        return service.createBlock(input);
    }

    @PutMapping("/api/admin/academic-blocks/{id}")
    public BlockItem updateBlock(@PathVariable String id, @RequestBody BlockInput input) {
        return service.updateBlock(id, input);
    }

    @DeleteMapping("/api/admin/academic-blocks/{id}")
    public Map<String, Object> deleteBlock(@PathVariable String id) {
        service.deleteBlock(id);
        return Map.of("deleted", true);
    }
}
