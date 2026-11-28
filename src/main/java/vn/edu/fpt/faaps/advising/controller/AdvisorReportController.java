package vn.edu.fpt.faaps.advising.controller;

import java.util.UUID;
import vn.edu.fpt.faaps.dashboard.service.AdvisingSemesterReportService;
import vn.edu.fpt.faaps.dashboard.service.AdvisingSemesterReportService.AdvisorSemesterReport;
import vn.edu.fpt.faaps.dashboard.service.AdvisingSemesterReportService.SemesterActivity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/advisor/reports", "/advisor/reports"})
public class AdvisorReportController {
    private static final List<String> OPEN_SESSION_STATUSES =
            List.of("PENDING", "ACCEPTED", "SCHEDULED", "IN_PROGRESS");

    private final AdvisorReportQueryHelper queries;
    private final AdvisorAtRiskService atRiskService;

    private final AdvisingSemesterReportService semesterReports;

    public AdvisorReportController(JdbcTemplate jdbc, AdvisorAtRiskService atRiskService) {
        this(jdbc, atRiskService, new AdvisingSemesterReportService(jdbc, atRiskService));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdvisorReportController(JdbcTemplate jdbc, AdvisorAtRiskService atRiskService,
            AdvisingSemesterReportService semesterReports) {
        this.queries = new AdvisorReportQueryHelper(jdbc);
        this.atRiskService = atRiskService;
        this.semesterReports = semesterReports;
    }

    /** Flow 5: this advisor's advising activity in one semester (the current one when none is given). */
    @GetMapping("/semester")
    public AdvisorSemesterReport semester(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) UUID semesterId) {
        return semesterReports.advisorReport(advisor.id(), semesterId);
    }

    @GetMapping("/semester-trend")
    public List<SemesterActivity> semesterTrend(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(defaultValue = "6") int limit) {
        return semesterReports.trend(advisor.id(), limit);
    }

    @GetMapping("/overview")
    public OverviewReport overview(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String advisorId = advisor.id().toString();
        LocalDateTime fromTs = queries.parseBoundary(from, false);
        LocalDateTime toTs = queries.parseBoundary(to, true);

        Map<String, Integer> dist = statusDistribution(advisor);
        int totalStudents = dist.values().stream().mapToInt(value -> value.intValue()).sum();
        int activeStudents = dist.getOrDefault("ACTIVE", 0);
        int probationCount = dist.getOrDefault("PROBATION", 0);
        int suspendedCount = dist.getOrDefault("SUSPENDED", 0);
        int atRiskStudents = atRiskService.counts(advisor.id()).total();
        double avgGpa = queries.averageGpa(advisorId);

        int pendingRequests = queries.countRequests(advisorId, fromTs, toTs);
        int openAppointments = queries.countSessions(advisorId, OPEN_SESSION_STATUSES, fromTs, toTs);
        int completedAppointments = queries.countSessions(advisorId, List.of("COMPLETED"), fromTs, toTs);
        int noShowCount = queries.countSessions(advisorId, List.of("NO_SHOW"), fromTs, toTs);

        return new OverviewReport(
                totalStudents, activeStudents, atRiskStudents, probationCount, suspendedCount,
                avgGpa, pendingRequests, openAppointments, completedAppointments, noShowCount);
    }

    @GetMapping("/gpa-trend")
    public GpaTrendReport gpaTrend(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return new GpaTrendReport(queries.gpaTrend(advisor.id().toString(), from, to));
    }

    @GetMapping("/status-distribution")
    public Map<String, Integer> statusDistribution(@AuthenticationPrincipal AuthSession advisor) {
        return queries.statusDistribution(advisor.id().toString());
    }

    public record OverviewReport(
            int totalStudents,
            int activeStudents,
            int atRiskStudents,
            int probationCount,
            int suspendedCount,
            double avgGpa,
            int pendingRequests,
            int openAppointments,
            int completedAppointments,
            int noShowCount
    ) {}

    public record SemesterGpaItem(
            String code,
            String name,
            double avgGpa,
            double minGpa,
            double maxGpa,
            int studentCount
    ) {}

    public record GpaTrendReport(List<SemesterGpaItem> semesters) {}
}
