package vn.edu.fpt.faaps.appointment.repository;

import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AdvisorAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;

public class AppointmentReadRepository {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String BOOKED_SELECT = """
            SELECT s.id, s.student_id, u.full_name AS student_name, st.student_code,
                   s.advisor_id, a.full_name AS advisor_name, s.scheduled_at, s.ended_at,
                   s.duration_minutes, s.agenda, s.status, s.summary, s.created_at
            FROM advising_sessions s JOIN users u ON u.id = s.student_id
            LEFT JOIN students st ON st.user_id = s.student_id JOIN users a ON a.id = s.advisor_id
            """;
    private final JdbcTemplate jdbc;

    public AppointmentReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AvailabilitySlotDto> availability(UUID advisorId) {
        return jdbc.query("""
                SELECT day_of_week, start_time, end_time FROM advisor_availability
                WHERE advisor_id = ? AND is_active = TRUE ORDER BY start_time
                """, (rs, row) -> new AvailabilitySlotDto(dayOfWeek(rs.getString("day_of_week")),
                time(rs.getTime("start_time"), "08:00"), time(rs.getTime("end_time"), "17:00")),
                advisorId.toString());
    }

    public List<AdvisorAppointmentDto> advisorAppointments(UUID advisorId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT s.id, s.student_id, u.full_name AS student_name, st.student_code,
                       s.scheduled_at, s.ended_at, s.duration_minutes, s.agenda, s.status,
                       s.summary, s.created_at
                FROM advising_sessions s JOIN users u ON u.id = s.student_id
                LEFT JOIN students st ON st.user_id = s.student_id
                WHERE s.advisor_id = ? AND s.scheduled_at >= ? AND s.scheduled_at <= ?
                ORDER BY s.scheduled_at ASC
                """, this::mapAdvisor, advisorId.toString(), Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    public List<BookedAppointmentDto> studentAppointments(UUID studentId) {
        return jdbc.query(BOOKED_SELECT + " WHERE s.student_id = ? ORDER BY s.scheduled_at DESC",
                this::mapBooked, studentId.toString());
    }

    public BookedAppointmentDto appointment(UUID appointmentId) {
        return jdbc.query(BOOKED_SELECT + " WHERE s.id = ?", this::mapBooked, appointmentId.toString())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found"));
    }

    public UUID assignedAdvisor(UUID studentId) {
        List<String> assigned = jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL LIMIT 1
                """, (rs, row) -> rs.getString(1), studentId.toString());
        if (!assigned.isEmpty()) return UUID.fromString(assigned.get(0));
        return jdbc.query("SELECT user_id FROM advisors LIMIT 1", (rs, row) -> rs.getString(1))
                .stream().findFirst().map(UUID::fromString).orElse(null);
    }

    private AdvisorAppointmentDto mapAdvisor(ResultSet rs, int row) throws SQLException {
        LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
        LocalDateTime end = end(rs, start);
        Timestamp created = rs.getTimestamp("created_at");
        return new AdvisorAppointmentDto(rs.getString("id"), rs.getString("student_id"),
                rs.getString("student_name"), rs.getString("student_code"), start.format(ISO_DT),
                end.format(ISO_DT), rs.getString("agenda"), rs.getString("status"),
                rs.getString("summary"), created == null ? null : created.toLocalDateTime().format(ISO_DT));
    }

    private BookedAppointmentDto mapBooked(ResultSet rs, int row) throws SQLException {
        LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
        LocalDateTime end = end(rs, start);
        Timestamp created = rs.getTimestamp("created_at");
        return new BookedAppointmentDto(rs.getString("id"), rs.getString("student_id"),
                rs.getString("student_name"), rs.getString("student_code"), rs.getString("advisor_id"),
                rs.getString("advisor_name"), start.format(ISO_DT), end.format(ISO_DT),
                rs.getString("agenda"), rs.getString("status"), rs.getString("summary"),
                created == null ? null : created.toLocalDateTime().format(ISO_DT));
    }

    private LocalDateTime end(ResultSet rs, LocalDateTime start) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        int duration = rs.getInt("duration_minutes");
        return ended == null ? start.plusMinutes(duration <= 0 ? 30 : duration) : ended.toLocalDateTime();
    }

    private int dayOfWeek(String value) {
        if (value == null || value.isBlank()) return 1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            try {
                return DayOfWeek.valueOf(value.trim().toUpperCase(Locale.ROOT)).getValue();
            } catch (IllegalArgumentException invalid) {
                return 1;
            }
        }
    }

    private String time(Time value, String fallback) {
        return value == null ? fallback : value.toLocalTime().format(TIME_FMT);
    }
}
