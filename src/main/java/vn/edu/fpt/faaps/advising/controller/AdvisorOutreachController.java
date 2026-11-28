package vn.edu.fpt.faaps.advising.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.service.AdvisorOutreachService;
import vn.edu.fpt.faaps.advising.service.AdvisorOutreachService.SemesterOption;
import vn.edu.fpt.faaps.advising.service.AdvisorOutreachService.StudentSignals;
import vn.edu.fpt.faaps.auth.model.AuthSession;

/** Flow 3: data an advisor needs to decide how to reach out to a flagged student. */
@RestController
@RequestMapping({"/api/advisor/outreach", "/advisor/outreach"})
public class AdvisorOutreachController {
    private final AdvisorOutreachService outreach;

    public AdvisorOutreachController(AdvisorOutreachService outreach) {
        this.outreach = outreach;
    }

    @GetMapping("/semesters")
    public List<SemesterOption> semesters() {
        return outreach.semesters();
    }

    @GetMapping("/students/{studentId}/signals")
    public StudentSignals signals(
            @PathVariable UUID studentId,
            @RequestParam(required = false) UUID semesterId,
            @AuthenticationPrincipal AuthSession advisor) {
        return outreach.signals(advisor.id(), studentId, semesterId);
    }
}
