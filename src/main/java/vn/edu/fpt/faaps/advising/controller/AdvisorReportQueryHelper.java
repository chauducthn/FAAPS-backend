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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.advising.controller.AdvisorReportController.SemesterGpaItem;

class AdvisorReportQueryHelper {
    private static final Logger log = LoggerFactory.getLogger(AdvisorReportQueryHelper.class);
    private final JdbcTemplate jdbc;

    AdvisorReportQueryHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    double averageGpa(String advisorId) {
        Double avgObj = jdbc.queryForObject("""
                SELECT AVG(s.current_gpa)
                FROM student_advisor_assignments saa
                JOIN students s ON s.user_id = saa.student_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                  AND s.current_gpa IS NOT NULL AND s.current_gpa > 0
                """, Double.class, advisorId);
        return avgObj == null
                ? 0.0
                : BigDecimal.valueOf(avgObj).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    List<SemesterGpaItem> gpaTrend(String advisorId, String from, String to) {
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

        try {
            return jdbc.query(sql.toString(), (rs, row) -> new SemesterGpaItem(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getObject("avg_gpa") != null ? rs.getDouble("avg_gpa") : 0.0,
                    rs.getObject("min_gpa") != null ? rs.getDouble("min_gpa") : 0.0,
                    rs.getObject("max_gpa") != null ? rs.getDouble("max_gpa") : 0.0,
                    rs.getInt("student_count")
            ), params.toArray());
        } catch (Exception ex) {
            log.debug("Failed to load GPA trend for advisor {}: {}", advisorId, ex.getMessage());
            return List.of();
        }
    }

    Map<String, Integer> statusDistribution(String advisorId) {
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
                """, rs -> {
            String key = rs.getString("status_code");
            if (key != null && dist.containsKey(key.toUpperCase())) {
                dist.put(key.toUpperCase(), rs.getInt("cnt"));
            }
        }, advisorId);
        return dist;
    }

    int countRequests(String advisorId, LocalDateTime fromTs, LocalDateTime toTs) {
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT COUNT(*) FROM advising_requests
                    WHERE advisor_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                    """);
            List<Object> params = new ArrayList<>(List.of(advisorId));
            appendTimeRange(sql, params, "created_at", fromTs, toTs);
            Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count == null ? 0 : count;
        } catch (Exception ex) {
            log.debug("Failed to count requests for advisor {}: {}", advisorId, ex.getMessage());
            return 0;
        }
    }

    int countSessions(String advisorId, List<String> statuses, LocalDateTime fromTs, LocalDateTime toTs) {
        try {
            String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
            StringBuilder sql = new StringBuilder(
                    "SELECT COUNT(*) FROM advising_sessions WHERE advisor_id = ? AND status IN ("
                            + placeholders + ")");
            List<Object> params = new ArrayList<>();
            params.add(advisorId);
            params.addAll(statuses);
            appendTimeRange(sql, params, "scheduled_at", fromTs, toTs);
            Integer count = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
            return count == null ? 0 : count;
        } catch (Exception ex) {
            log.debug("Failed to count sessions for advisor {}: {}", advisorId, ex.getMessage());
            return 0;
        }
    }

    LocalDateTime parseBoundary(String raw, boolean endOfDay) {
        LocalDate date = parseDateOnly(raw);
        if (date == null) {
            return null;
        }
        return endOfDay ? date.plusDays(1).atStartOfDay().minusNanos(1) : date.atStartOfDay();
    }

    private static void appendTimeRange(
            StringBuilder sql, List<Object> params, String column,
            LocalDateTime fromTs, LocalDateTime toTs) {
        if (fromTs != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(Timestamp.valueOf(fromTs));
        }
        if (toTs != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            params.add(Timestamp.valueOf(toTs));
        }
    }

    private static LocalDate parseDateOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip().substring(0, 10));
        } catch (Exception ex) {
            log.debug("Ignoring unparseable report date '{}': {}", raw, ex.getMessage());
            return null;
        }
    }
}
