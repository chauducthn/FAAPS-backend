package vn.edu.fpt.faaps.dashboard.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.dashboard.service.AdminAdvisingReportService;
import vn.edu.fpt.faaps.dashboard.service.AdminAdvisingReportService.AdvisorEffectivenessRow;
import vn.edu.fpt.faaps.dashboard.service.AdminAdvisingReportService.SchoolAdvisingOverview;

@RestController
@RequestMapping("/api/admin/reports/advising")
public class AdminAdvisingReportController {
    private final AdminAdvisingReportService reports;

    public AdminAdvisingReportController(AdminAdvisingReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/overview")
    public SchoolAdvisingOverview overview(@RequestParam(required = false) UUID semesterId) {
        return reports.overview(semesterId);
    }

    @GetMapping("/by-advisor")
    public List<AdvisorEffectivenessRow> byAdvisor(
            @RequestParam(required = false) UUID semesterId,
            @RequestParam(defaultValue = "50") int limit) {
        return reports.byAdvisor(semesterId, limit);
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) UUID semesterId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=faaps-advising-report.csv")
                .body(reports.exportCsv(semesterId));
    }
}
