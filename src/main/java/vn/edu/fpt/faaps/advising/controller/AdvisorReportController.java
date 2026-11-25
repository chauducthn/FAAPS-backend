package vn.edu.fpt.faaps.advising.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final JdbcTemplate jdbc;
    private final AdvisorAtRiskService atRiskService;

    public AdvisorReportController(JdbcTemplate jdbc, AdvisorAtRiskService atRiskService) {
        this.jdbc = jdbc;
        this.atRiskService = atRiskService;
    }

    @GetMapping("/overview")
    public OverviewReport overview(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String advisorId = advisor.id().toString();
        LocalDateTime fromTs = parseBoundary(from, false);
        LocalDateTime toTs = parseBoundary(to, true);

        Map<String, Integer> dist = statusDistribution(advisor);
        int totalStudents = dist.values().stream().mapToInt(Integer::intValue).sum();
        int activeStudents = dist.getOrDefault("ACTIVE", 0);
        int probationCount = dist.getOrDefault("PROBATION", 0);
        int suspendedCount = dist.getOrDefault("SUSPENDED", 0);
        int atRiskStudents = atRiskService.counts(advisor.id()).total();

        Double avgObj = jdbc.queryForObject("""
                SELECT AVG(s.current_gpa)
                FROM student_advisor_assignments saa
                JOIN students s ON s.user_id = saa.student_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                  AND s.current_gpa IS NOT NULL AND s.current_gpa > 0
                """, Double.class, advisorId);
        double avgGpa = avgObj == null ? 0.0 : BigDecimal.valueOf(avgObj).setScale(2, RoundingMode.HALF_UP).doubleValue();

        int pendingRequests = countRequests(advisorId, fromTs, toTs);
        int openAppointments = countSessions(advisorId, List.of("PENDING", "ACCEPTED", "SCHEDULED", "IN_PROGRESS"), fromTs, toTs);
        int completedAppointments = countSessions(advisorId, List.of("COMPLETED"), fromTs, toTs);
        int noShowCount = countSessions(advisorId, List.of("NO_SHOW"), fromTs, toTs);

        return new OverviewReport(
                totalStudents,
                activeStudents,
                atRiskStudents,
                probationCount,
                suspendedCount,
                avgGpa,
                pendingRequests,
                openAppointments,
                completedAppointments,
                noShowCount
        );
    }

    @GetMapping("/gpa-trend")
    public GpaTrendReport gpaTrend(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String advisorId = advisor.id().toString();
        LocalDate fromDate = parseDateOnly(from);
        LocalDate toDate = parseDateOnly(to);

        StringBuilder sql = new StringBuilder("""
                SELECT sem.code, sem.name,
                       ROUND(AVG(ss.semester_gpa), 2) AS avg_gpa,
                       ROUND(MIN(ss.semester_gpa), 2) AS min_gpa,
                       ROUND(MAX(ss.semester_gpa), 2) AS max_gpa,
                       COUNT(DISTINCT ss.student_id) AS student_count
                FROM semester_summaries ss
                JOIN semesters sem ON sem.id = ss.semester_id
                JOIN student_advisor_assignments saa ON saa.student_id = ss.student_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                """);
        List<Object> params = new ArrayList<>(List.of(advisorId));
        if (fromDate != null) {
            sql.append(" AND sem.start_date >= ?");
            params.add(java.sql.Date.valueOf(fromDate));
        }
        if (toDate != null) {
            sql.append(" AND sem.start_date <= ?");
            params.add(java.sql.Date.valueOf(toDate));
        }
        sql.append(" GROUP BY sem.id, sem.code, sem.name, sem.start_date ORDER BY sem.start_date ASC");

        List<SemesterGpaItem> items = new ArrayList<>();
        try {
            items = jdbc.query(sql.toString(), (rs, row) -> new SemesterGpaItem(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getObject("avg_gpa") != null ? rs.getDouble("avg_gpa") : 0.0,
                    rs.getObject("min_gpa") != null ? rs.getDouble("min_gpa") : 0.0,
                    rs.getObject("max_gpa") != null ? rs.getDouble("max_gpa") : 0.0,
                    rs.getInt("student_count")
            ), params.toArray());
        } catch (Exception ignored) {
        }
        return new GpaTrendReport(items);
    }

    @GetMapping("/status-distribution")
    public Map<String, Integer> statusDistribution(@AuthenticationPrincipal AuthSession advisor) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("ACTIVE", 0);
        dist.put("PROBATION", 0);
        dist.put("SUSPENDED", 0);
        dist.put("GRADUATED", 0);
        dist.put("WITHDRAWN", 0);
        dist.put("LEAVE_OF_ABSENCE", 0);

        jdbc.query("""
                SELECT COALESCE(s.academic_status, 'ACTIVE') AS status_code, COUNT(*) AS cnt
                FROM student_advisor_assignments saa
                JOIN students s ON s.user_id = saa.student_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                GROUP BY COALESCE(s.academic_status, 'ACTIVE')
                """, (rs) -> {
            String key = rs.getString("status_code");
            if (key != null && dist.containsKey(key.toUpperCase())) {
                dist.put(key.toUpperCase(), rs.getInt("cnt"));
            }
        }, advisor.id().toString());
        return dist;
    }

    private int countRequests(String advisorId, LocalDateTime fromTs, LocalDateTime toTs) {
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT COUNT(*) FROM advising_requests
                    WHERE advisor_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                    """);
            List<Object> params = new ArrayList<>(List.of(advisorId));
            if (fromTs != null) {
                sql.append(" AND created_at >= ?");
                params.add(Timestamp.valueOf(fromTs));
            }
            if (toTs != null) {
                sql.append(" AND created_at <= ?");
                params.add(Timestamp.valueOf(toTs));
            }
            Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int countSessions(String advisorId, List<String> statuses, LocalDateTime fromTs, LocalDateTime toTs) {
        try {
            String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM advising_sessions WHERE advisor_id = ? AND status IN (" + placeholders + ")");
            List<Object> params = new ArrayList<>();
            params.add(advisorId);
            params.addAll(statuses);
            if (fromTs != null) {
                sql.append(" AND scheduled_at >= ?");
                params.add(Timestamp.valueOf(fromTs));
            }
            if (toTs != null) {
                sql.append(" AND scheduled_at <= ?");
                params.add(Timestamp.valueOf(toTs));
            }
            Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static LocalDate parseDateOnly(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip().substring(0, 10));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDateTime parseBoundary(String raw, boolean endOfDay) {
        LocalDate date = parseDateOnly(raw);
        if (date == null) return null;
        return endOfDay ? date.plusDays(1).atStartOfDay().minusNanos(1) : date.atStartOfDay();
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
