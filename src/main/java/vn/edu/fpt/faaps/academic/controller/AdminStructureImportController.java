package vn.edu.fpt.faaps.academic.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportRequest;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportSummary;

@RestController
@RequestMapping("/api/admin/structure")
public class AdminStructureImportController {
    private final AdminStructureImportService service;

    public AdminStructureImportController(AdminStructureImportService service) {
        this.service = service;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportSummary> importStructure(@RequestBody ImportRequest request) {
        return ResponseEntity.ok(service.importBatch(request));
    }
}
