package vn.edu.fpt.faaps.academic.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportRequest;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportSummary;
import vn.edu.fpt.faaps.academic.service.FlmSubjectGroupService;
import vn.edu.fpt.faaps.academic.service.FlmSubjectGroupService.GroupImportRequest;
import vn.edu.fpt.faaps.academic.service.FlmSubjectGroupService.GroupImportSummary;

@RestController
@RequestMapping("/api/admin/structure")
public class AdminStructureImportController {
    private final AdminStructureImportService service;
    private final FlmSubjectGroupService groupService;

    public AdminStructureImportController(AdminStructureImportService service,
            FlmSubjectGroupService groupService) {
        this.service = service;
        this.groupService = groupService;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportSummary> importStructure(@RequestBody ImportRequest request) {
        return ResponseEntity.ok(service.importBatch(request));
    }

    @PostMapping("/import-groups")
    public ResponseEntity<GroupImportSummary> importFlmGroups(@RequestBody GroupImportRequest request) {
        return ResponseEntity.ok(groupService.importGroups(request));
    }
}
