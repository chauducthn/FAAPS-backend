package vn.edu.fpt.faaps.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAdvisingReportService {
    private final JdbcTemplate jdbc;

    public AdminAdvisingReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SchoolAdvisingOverview overview(UUID semesterId) {
        SemesterRange range = resolveSemesterRange(semesterId);
        int totalStudents = countInt("SELECT COUNT(*) FROM students");
        int atRiskStudents = countAtRisk(range);
        int contactedAtRisk = countContactedAtRisk(range);
        double coverageRate = atRiskStudents == 0 ? 100.0
                : Math.round((contactedAtRisk * 1000.0) / atRiskStudents) / 10.0;

        int onlineSessions = countSessionsByType("VIRTUAL", range);
        int offlineSessions = countSessionsByType("SCHEDULED", range) + countSessionsByType("WALK_IN", range);
        int emailSessions = countSessionsByType("EMAIL", range);
        int completedSessions = countSessionsByStatus("COMPLETED", range);
        int noShowSessions = countSessionsByStatus("NO_SHOW", range);
        int totalSessions = onlineSessions + offlineSessions + emailSessions;

        return new SchoolAdvisingOverview(
                range.semesterId(),
                range.semesterCode(),
                totalStudents,
                atRiskStudents,
                contactedAtRisk,
                coverageRate,
                totalSessions,
                onlineSessions,
                offlineSessions,
                emailSessions,
                completedSessions,
                noShowSessions);
    }

    public List<AdvisorEffectivenessRow> byAdvisor(UUID semesterId, int limit) {
        SemesterRange range = resolveSemesterRange(semesterId);
        int capped = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT u.id AS advisor_id, u.full_name, u.email,
                       (SELECT COUNT(*) FROM student_advisor_assignments sa
                        WHERE sa.advisor_id = u.id AND sa.status = 'ACTIVE') AS assigned_students,
                       (SELECT COUNT(*) FROM advising_sessions s
                        WHERE s.advisor_id = u.id AND s.status = 'COMPLETED'
                          AND (? IS NULL OR s.scheduled_at >= ?)
                          AND (? IS NULL OR s.scheduled_at < ?)) AS completed_sessions,
                       (SELECT COUNT(*) FROM advising_sessions s
                        WHERE s.advisor_id = u.id AND s.session_type = 'EMAIL'
                          AND (? IS NULL OR s.scheduled_at >= ?)
                          AND (? IS NULL OR s.scheduled_at < ?)) AS email_outreaches,
                       (SELECT COUNT(*) FROM advising_requests r
                        WHERE r.advisor_id = u.id AND r.status = 'PENDING') AS pending_requests
                FROM advisors a
                JOIN users u ON u.id = a.user_id
                ORDER BY completed_sessions DESC, u.full_name ASC
                LIMIT ?
                """,
                (rs, row) -> new AdvisorEffectivenessRow(
                        rs.getString("advisor_id"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getInt("assigned_students"),
                        rs.getInt("completed_sessions"),
                        rs.getInt("email_outreaches"),
                        rs.getInt("pending_requests")),
                range.startTs(), range.startTs(), range.endTs(), range.endTs(),
                range.startTs(), range.startTs(), range.endTs(), range.endTs(),
                capped);
    }

    public byte[] exportCsv(UUID semesterId) {
        SchoolAdvisingOverview summary = overview(semesterId);
        List<AdvisorEffectivenessRow> advisors = byAdvisor(semesterId, 100);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Semester,Total Students,At-Risk Students,Contacted At-Risk,Coverage %,Total Sessions,Online,Offline,Email,Completed,No-Show\r\n");
        csv.append(String.format("%s,%d,%d,%d,%.1f,%d,%d,%d,%d,%d,%d\r\n\r\n",
                csvCell(summary.semesterCode()), summary.totalStudents(), summary.atRiskStudents(),
                summary.contactedAtRisk(), summary.coverageRate(), summary.totalSessions(),
                summary.onlineSessions(), summary.offlineSessions(), summary.emailSessions(),
                summary.completedSessions(), summary.noShowSessions()));
        csv.append("Advisor Name,Email,Assigned Students,Completed Sessions,Email Outreaches,Pending Requests\r\n");
        for (AdvisorEffectivenessRow row : advisors) {
            csv.append(String.format("%s,%s,%d,%d,%d,%d\r\n",
                    csvCell(row.fullName()), csvCell(row.email()), row.assignedStudents(),
                    row.completedSessions(), row.emailOutreaches(), row.pendingRequests()));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private SemesterRange resolveSemesterRange(UUID semesterId) {
        if (semesterId == null) {
            return new SemesterRange(null, "ALL", null, null);
        }
        List<SemesterRange> rows = jdbc.query(
                "SELECT id, code, start_date, end_date FROM semesters WHERE id = ?",
                (rs, row) -> {
                    LocalDate start = rs.getObject("start_date", LocalDate.class);
                    LocalDate end = rs.getObject("end_date", LocalDate.class);
                    return new SemesterRange(
                            rs.getString("id"),
                            rs.getString("code"),
                            start == null ? null : Timestamp.valueOf(start.atStartOfDay()),
                            end == null ? null : Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
                },
                semesterId.toString());
        return rows.isEmpty() ? new SemesterRange(null, "ALL", null, null) : rows.get(0);
    }

    private int countAtRisk(SemesterRange range) {
        if (range.semesterId() == null) {
            return countInt("SELECT COUNT(DISTINCT student_id) FROM academic_warnings WHERE resolved_at IS NULL");
        }
        return countInt("SELECT COUNT(DISTINCT student_id) FROM academic_warnings WHERE semester_id = ?", range.semesterId());
    }

    private int countContactedAtRisk(SemesterRange range) {
        return countInt("""
                SELECT COUNT(DISTINCT w.student_id)
                FROM academic_warnings w
                JOIN advising_sessions s ON s.student_id = w.student_id
                WHERE (? IS NULL OR w.semester_id = ?)
                  AND (? IS NULL OR s.scheduled_at >= ?)
                  AND (? IS NULL OR s.scheduled_at < ?)
                """, range.semesterId(), range.semesterId(), range.startTs(), range.startTs(), range.endTs(), range.endTs());
    }

    private int countSessionsByType(String type, SemesterRange range) {
        return countInt("""
                SELECT COUNT(*) FROM advising_sessions
                WHERE session_type = ?
                  AND (? IS NULL OR scheduled_at >= ?)
                  AND (? IS NULL OR scheduled_at < ?)
                """, type, range.startTs(), range.startTs(), range.endTs(), range.endTs());
    }

    private int countSessionsByStatus(String status, SemesterRange range) {
        return countInt("""
                SELECT COUNT(*) FROM advising_sessions
                WHERE status = ?
                  AND (? IS NULL OR scheduled_at >= ?)
                  AND (? IS NULL OR scheduled_at < ?)
                """, status, range.startTs(), range.startTs(), range.endTs(), range.endTs());
    }

    private int countInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String csvCell(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return safe.contains(",") ? "\"" + safe + "\"" : safe;
    }

    private record SemesterRange(String semesterId, String semesterCode, Timestamp startTs, Timestamp endTs) {}

    public record SchoolAdvisingOverview(
            String semesterId,
            String semesterCode,
            int totalStudents,
            int atRiskStudents,
            int contactedAtRisk,
            double coverageRate,
            int totalSessions,
            int onlineSessions,
            int offlineSessions,
            int emailSessions,
            int completedSessions,
            int noShowSessions) {}

    public record AdvisorEffectivenessRow(
            String advisorId,
            String fullName,
            String email,
            int assignedStudents,
            int completedSessions,
            int emailOutreaches,
            int pendingRequests) {}
}
