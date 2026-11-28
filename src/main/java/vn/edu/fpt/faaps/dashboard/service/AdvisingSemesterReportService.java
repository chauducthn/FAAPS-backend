package vn.edu.fpt.faaps.dashboard.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService.AtRiskStudent;

/**
 * Flow 5: advising activity per semester, for one advisor or for the whole school.
 *
 * <p>Sessions are counted by {@code scheduled_at} (email advising is stored with the send time), requests by
 * {@code created_at}. Without a semester id the current semester is used, so reports default to "this term".
 */
@Service
public class AdvisingSemesterReportService {
    private final JdbcTemplate jdbc;
    private final AdvisorAtRiskService atRisk;

    public AdvisingSemesterReportService(JdbcTemplate jdbc, AdvisorAtRiskService atRisk) {
        this.jdbc = jdbc;
        this.atRisk = atRisk;
    }

    public AdvisorSemesterReport advisorReport(UUID advisorId, UUID semesterId) {
        Semester semester = resolve(semesterId);
        String advisor = advisorId.toString();
        SessionCounts sessions = sessionCounts(advisor, semester);

        List<AtRiskStudent> flagged = atRisk.computeAllAtRisk(advisorId, semester.id() == null ? null
                : UUID.fromString(semester.id()));
        List<String> contactedIds = jdbc.query("""
                SELECT DISTINCT student_id FROM advising_sessions
                WHERE advisor_id = ? AND status <> 'CANCELLED'
                  AND (? IS NULL OR scheduled_at >= ?) AND (? IS NULL OR scheduled_at < ?)
                """, (rs, row) -> rs.getString(1), advisor, semester.start(), semester.start(), semester.end(),
                semester.end());
        List<OutstandingStudent> outstanding = new ArrayList<>();
        int contacted = 0;
        for (AtRiskStudent student : flagged) {
            if (contactedIds.contains(student.id())) {
                contacted++;
            } else if (outstanding.size() < 8) {
                outstanding.add(new OutstandingStudent(student.id(), student.fullName(), student.studentCode(),
                        student.riskLevel(), student.reasons()));
            }
        }
        double coverage = flagged.isEmpty() ? 100.0 : Math.round(contacted * 1000.0 / flagged.size()) / 10.0;

        int assigned = count("""
                SELECT COUNT(*) FROM student_advisor_assignments
                WHERE advisor_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                """, advisor);
        int requestsReceived = count("""
                SELECT COUNT(*) FROM advising_requests WHERE advisor_id = ?
                  AND (? IS NULL OR created_at >= ?) AND (? IS NULL OR created_at < ?)
                """, advisor, semester.start(), semester.start(), semester.end(), semester.end());
        int requestsResolved = count("""
                SELECT COUNT(*) FROM advising_requests WHERE advisor_id = ? AND status = 'RESOLVED'
                  AND (? IS NULL OR COALESCE(resolved_at, updated_at) >= ?)
                  AND (? IS NULL OR COALESCE(resolved_at, updated_at) < ?)
                """, advisor, semester.start(), semester.start(), semester.end(), semester.end());
        int withSummary = count("""
                SELECT COUNT(*) FROM advising_sessions WHERE advisor_id = ? AND status = 'COMPLETED'
                  AND COALESCE(NULLIF(TRIM(consultation_summary), ''), NULLIF(TRIM(summary), '')) IS NOT NULL
                  AND (? IS NULL OR scheduled_at >= ?) AND (? IS NULL OR scheduled_at < ?)
                """, advisor, semester.start(), semester.start(), semester.end(), semester.end());

        return new AdvisorSemesterReport(semester.id(), semester.code(), semester.name(), assigned, flagged.size(),
                contacted, coverage, sessions, requestsReceived, requestsResolved, withSummary, outstanding);
    }

    /** The last {@code limit} semesters that have started, oldest first; advisorId null means every advisor. */
    public List<SemesterActivity> trend(UUID advisorId, int limit) {
        int capped = Math.max(1, Math.min(limit, 12));
        List<Semester> semesters = jdbc.query("""
                SELECT id, code, name, start_date, end_date FROM semesters
                WHERE start_date <= ? ORDER BY start_date DESC LIMIT ?
                """, (rs, row) -> semester(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                LocalDate.now(), capped);
        String advisor = advisorId == null ? null : advisorId.toString();
        List<SemesterActivity> result = new ArrayList<>();
        for (int i = semesters.size() - 1; i >= 0; i--) {
            Semester semester = semesters.get(i);
            SessionCounts sessions = sessionCounts(advisor, semester);
            int students = count("""
                    SELECT COUNT(DISTINCT student_id) FROM advising_sessions
                    WHERE (? IS NULL OR advisor_id = ?) AND status <> 'CANCELLED'
                      AND scheduled_at >= ? AND scheduled_at < ?
                    """, advisor, advisor, semester.start(), semester.end());
            int resolved = count("""
                    SELECT COUNT(*) FROM advising_requests
                    WHERE (? IS NULL OR advisor_id = ?) AND status = 'RESOLVED'
                      AND COALESCE(resolved_at, updated_at) >= ? AND COALESCE(resolved_at, updated_at) < ?
                    """, advisor, advisor, semester.start(), semester.end());
            int warnings = advisor == null ? count("""
                    SELECT COUNT(DISTINCT student_id) FROM academic_warnings WHERE semester_id = ?
                    """, semester.id()) : count("""
                    SELECT COUNT(DISTINCT w.student_id) FROM academic_warnings w
                    JOIN student_advisor_assignments a ON a.student_id = w.student_id
                    WHERE w.semester_id = ? AND a.advisor_id = ? AND a.status = 'ACTIVE'
                    """, semester.id(), advisor);
            result.add(new SemesterActivity(semester.id(), semester.code(), semester.name(), sessions,
                    students, resolved, warnings));
        }
        return result;
    }

    private SessionCounts sessionCounts(String advisor, Semester semester) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN session_type = 'VIRTUAL' THEN 1 ELSE 0 END) AS online,
                       SUM(CASE WHEN session_type IN ('SCHEDULED', 'WALK_IN') THEN 1 ELSE 0 END) AS offline,
                       SUM(CASE WHEN session_type = 'EMAIL' THEN 1 ELSE 0 END) AS email,
                       SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed,
                       SUM(CASE WHEN status = 'NO_SHOW' THEN 1 ELSE 0 END) AS no_show,
                       SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,
                       SUM(CASE WHEN status IN ('SCHEDULED', 'PENDING', 'ACCEPTED', 'IN_PROGRESS') THEN 1 ELSE 0 END)
                           AS upcoming
                FROM advising_sessions
                WHERE (? IS NULL OR advisor_id = ?)
                  AND (? IS NULL OR scheduled_at >= ?) AND (? IS NULL OR scheduled_at < ?)
                """, advisor, advisor, semester.start(), semester.start(), semester.end(), semester.end());
        return new SessionCounts(num(row.get("total")), num(row.get("online")), num(row.get("offline")),
                num(row.get("email")), num(row.get("completed")), num(row.get("no_show")),
                num(row.get("cancelled")), num(row.get("upcoming")));
    }

    private Semester resolve(UUID semesterId) {
        List<Semester> rows;
        if (semesterId != null) {
            rows = jdbc.query("SELECT id, code, name, start_date, end_date FROM semesters WHERE id = ?",
                    (rs, row) -> semester(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                            rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                    semesterId.toString());
        } else {
            rows = jdbc.query("""
                    SELECT id, code, name, start_date, end_date FROM semesters
                    WHERE start_date <= ? ORDER BY is_current DESC, start_date DESC LIMIT 1
                    """, (rs, row) -> semester(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                    rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                    LocalDate.now());
        }
        return rows.isEmpty() ? new Semester(null, "ALL", "All time", null, null) : rows.get(0);
    }

    private static Semester semester(String id, String code, String name, LocalDate start, LocalDate end) {
        return new Semester(id, code, name, start == null ? null : Timestamp.valueOf(start.atStartOfDay()),
                end == null ? null : Timestamp.valueOf(end.plusDays(1).atStartOfDay()));
    }

    private int count(String sql, Object... args) {
        try {
            Integer value = jdbc.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static int num(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record Semester(String id, String code, String name, Timestamp start, Timestamp end) {}

    public record SessionCounts(int total, int online, int offline, int email, int completed, int noShow,
            int cancelled, int upcoming) {}

    public record OutstandingStudent(String id, String fullName, String studentCode, String riskLevel,
            List<String> reasons) {}

    public record AdvisorSemesterReport(String semesterId, String semesterCode, String semesterName,
            int assignedStudents, int atRiskStudents, int contactedAtRisk, double coverageRate,
            SessionCounts sessions, int requestsReceived, int requestsResolved, int completedWithSummary,
            /** Flagged students this advisor has not contacted this semester, highest risk first. */
            List<OutstandingStudent> outstanding) {}

    public record SemesterActivity(String semesterId, String semesterCode, String semesterName,
            SessionCounts sessions, int studentsAdvised, int requestsResolved, int flaggedStudents) {}
}
