package vn.edu.fpt.faaps.dashboard.controller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController.Appointment;
import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController.Page;

final class AdminAppointmentQuery {

    private static final Logger log = LoggerFactory.getLogger(AdminAppointmentQuery.class);
    private static final List<String> VALID_STATUSES = List.of(
            "PENDING", "ACCEPTED", "REJECTED", "SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "NO_SHOW");
    private static final String FROM_CLAUSE =
            " FROM advising_sessions session"
                    + " JOIN users student ON student.id = session.student_id"
                    + " JOIN users advisor ON advisor.id = session.advisor_id";
    private static final String SELECT_FULL = """
            SELECT session.id, session.student_id, student.full_name AS student_name,
                   session.advisor_id, advisor.full_name AS advisor_name,
                   session.scheduled_at, session.ended_at, session.location,
                   session.agenda, session.summary, session.consultation_summary, session.status
            """;
    private static final String SELECT_WITHOUT_CONSULTATION_SUMMARY = """
            SELECT session.id, session.student_id, student.full_name AS student_name,
                   session.advisor_id, advisor.full_name AS advisor_name,
                   session.scheduled_at, session.ended_at, session.location,
                   session.agenda, session.summary, NULL AS consultation_summary, session.status
            """;
    private static final String SELECT_MINIMAL = """
            SELECT session.id, session.student_id, student.full_name AS student_name,
                   session.advisor_id, advisor.full_name AS advisor_name,
                   session.scheduled_at, session.ended_at, session.location,
                   session.agenda, session.status
            """;

    private final JdbcTemplate jdbc;

    AdminAppointmentQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page<Appointment> list(String status, String search, UUID advisorId,
            String fromDate, String toDate, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)
                && !VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> values = new ArrayList<>();
        appendFilters(where, values, status, search, advisorId, fromDate, toDate);

        long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM_CLAUSE + where, Long.class, values.toArray());
        values.add(size);
        values.add(page * size);
        String suffix = FROM_CLAUSE + where + " ORDER BY session.scheduled_at DESC, session.id DESC LIMIT ? OFFSET ?";
        List<Appointment> items = queryWithSchemaFallback(suffix, values.toArray());
        return new Page<>(items, page, size, total, (total + size - 1) / size);
    }

    Optional<Appointment> findById(UUID id) {
        String suffix = FROM_CLAUSE + " WHERE session.id = ?";
        return queryWithSchemaFallback(suffix, new Object[] {id.toString()}).stream().findFirst();
    }

    private void appendFilters(StringBuilder where, List<Object> values, String status,
            String search, UUID advisorId, String fromDate, String toDate) {
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND session.status = ?");
            values.add(status);
        }
        if (advisorId != null) {
            where.append(" AND session.advisor_id = ?");
            values.add(advisorId.toString());
        }
        if (fromDate != null && !fromDate.isBlank()) {
            try {
                LocalDate from = LocalDate.parse(fromDate.strip());
                where.append(" AND session.scheduled_at >= ?");
                values.add(Timestamp.valueOf(from.atStartOfDay()));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("fromDate must use yyyy-MM-dd");
            }
        }
        if (toDate != null && !toDate.isBlank()) {
            try {
                LocalDate to = LocalDate.parse(toDate.strip());
                where.append(" AND session.scheduled_at < ?");
                values.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("toDate must use yyyy-MM-dd");
            }
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(student.full_name) LIKE ? OR LOWER(advisor.full_name) LIKE ?"
                    + " OR LOWER(session.agenda) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            values.add(term);
            values.add(term);
            values.add(term);
        }
    }

    private List<Appointment> queryWithSchemaFallback(String suffix, Object[] params) {
        try {
            return jdbc.query(SELECT_FULL + suffix, (rs, row) -> mapAppointment(rs), params);
        } catch (Exception ex1) {
            log.debug("Full appointment query failed, retrying without consultation_summary: {}", ex1.getMessage());
            try {
                return jdbc.query(SELECT_WITHOUT_CONSULTATION_SUMMARY + suffix,
                        (rs, row) -> mapAppointment(rs), params);
            } catch (Exception ex2) {
                log.debug("Summary appointment query failed, retrying with minimal columns: {}", ex2.getMessage());
                return jdbc.query(SELECT_MINIMAL + suffix, (rs, row) -> mapAppointment(rs), params);
            }
        }
    }

    private Appointment mapAppointment(ResultSet result) throws SQLException {
        String status = result.getString("status");
        String summary = optionalString(result, "summary");
        String dedicatedCs = optionalString(result, "consultation_summary");
        boolean completed = "COMPLETED".equalsIgnoreCase(status);
        String consultationSummary = dedicatedCs != null ? dedicatedCs : (completed ? summary : null);
        String decisionNote = (dedicatedCs != null && dedicatedCs.equals(summary) && completed)
                ? null
                : (completed && dedicatedCs == null ? null : summary);
        Timestamp scheduledAt = result.getTimestamp("scheduled_at");
        Timestamp endedAt = result.getTimestamp("ended_at");
        return new Appointment(
                result.getString("id"), result.getString("student_id"), result.getString("student_name"),
                result.getString("advisor_id"), result.getString("advisor_name"),
                scheduledAt == null ? null : scheduledAt.toLocalDateTime(),
                endedAt == null ? null : endedAt.toLocalDateTime(),
                result.getString("location"), result.getString("agenda"),
                decisionNote, consultationSummary, status);
    }

    private String optionalString(ResultSet result, String column) {
        try {
            return result.getString(column);
        } catch (Exception ex) {
            log.debug("Optional column {} unavailable in appointment result set: {}", column, ex.getMessage());
            return null;
        }
    }
}
