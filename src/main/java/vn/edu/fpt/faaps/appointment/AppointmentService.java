package vn.edu.fpt.faaps.appointment;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AdvisorAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AvailableSlotDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.CancelAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.ConfigureAvailabilityDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.RescheduleAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.UpdateAppointmentStatusDto;

@Service
public class AppointmentService {

    private final JdbcTemplate jdbc;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public AppointmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AvailabilitySlotDto> getAdvisorAvailability(UUID advisorId) {
        return jdbc.query("""
                SELECT day_of_week, start_time, end_time
                FROM advisor_availability
                WHERE advisor_id = ? AND is_active = TRUE
                ORDER BY start_time
                """, (rs, rowNum) -> {
            String dowStr = rs.getString("day_of_week");
            int dow = parseDayOfWeek(dowStr);
            Time st = rs.getTime("start_time");
            Time et = rs.getTime("end_time");
            String stStr = st != null ? st.toLocalTime().format(TIME_FMT) : "08:00";
            String etStr = et != null ? et.toLocalTime().format(TIME_FMT) : "17:00";
            return new AvailabilitySlotDto(dow, stStr, etStr);
        }, advisorId.toString());
    }

    @Transactional
    public void configureAdvisorAvailability(UUID advisorId, ConfigureAvailabilityDto body) {
        if (body.slots() == null) {
            throw new IllegalArgumentException("slots cannot be null");
        }
        for (AvailabilitySlotDto slot : body.slots()) {
            if (slot.dayOfWeek() < 1 || slot.dayOfWeek() > 7) {
                throw new IllegalArgumentException("dayOfWeek must be between 1 and 7");
            }
            LocalTime st = LocalTime.parse(slot.startTime(), TIME_FMT);
            LocalTime et = LocalTime.parse(slot.endTime(), TIME_FMT);
            if (!st.isBefore(et)) {
                throw new IllegalArgumentException("startTime must be before endTime");
            }
        }

        jdbc.update("DELETE FROM advisor_availability WHERE advisor_id = ?", advisorId.toString());

        for (AvailabilitySlotDto slot : body.slots()) {
            DayOfWeek dow = DayOfWeek.of(slot.dayOfWeek());
            LocalTime st = LocalTime.parse(slot.startTime(), TIME_FMT);
            LocalTime et = LocalTime.parse(slot.endTime(), TIME_FMT);
            jdbc.update("""
                    INSERT INTO advisor_availability (id, advisor_id, day_of_week, start_time, end_time, is_active)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), advisorId.toString(), dow.name(),
                    Time.valueOf(st), Time.valueOf(et));
        }
    }

    public List<AvailableSlotDto> getAvailableSlots(LocalDate date, UUID requestedAdvisorId, UUID studentId) {
        UUID advisorId = requestedAdvisorId != null ? requestedAdvisorId : resolveAssignedAdvisor(studentId);
        if (advisorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No advisor assigned. Please specify an advisor.");
        }

        int isoDay = date.getDayOfWeek().getValue();
        String dayName = DayOfWeek.of(isoDay).name();
        String dayNum = String.valueOf(isoDay);

        List<Map<String, Object>> windows = jdbc.queryForList("""
                SELECT start_time, end_time
                FROM advisor_availability
                WHERE advisor_id = ? AND is_active = TRUE AND (day_of_week = ? OR day_of_week = ?)
                ORDER BY start_time
                """, advisorId.toString(), dayName, dayNum);

        if (windows.isEmpty()) {
            return List.of();
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        List<BookedInterval> existingSessions = jdbc.query("""
                SELECT scheduled_at, duration_minutes, ended_at
                FROM advising_sessions
                WHERE advisor_id = ?
                  AND status IN ('PENDING', 'ACCEPTED', 'SCHEDULED', 'IN_PROGRESS')
                  AND scheduled_at >= ? AND scheduled_at < ?
                """, (rs, rowNum) -> {
            LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
            int duration = rs.getInt("duration_minutes");
            if (duration <= 0) duration = 30;
            Timestamp endedTs = rs.getTimestamp("ended_at");
            LocalDateTime end = endedTs != null ? endedTs.toLocalDateTime() : start.plusMinutes(duration);
            return new BookedInterval(start, end);
        }, advisorId.toString(), Timestamp.valueOf(dayStart), Timestamp.valueOf(dayEnd));

        List<AvailableSlotDto> availableSlots = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> window : windows) {
            Time stVal = (Time) window.get("start_time");
            Time etVal = (Time) window.get("end_time");
            LocalTime windowStart = stVal.toLocalTime();
            LocalTime windowEnd = etVal.toLocalTime();

            LocalTime current = windowStart;
            while (!current.plusMinutes(30).isAfter(windowEnd)) {
                LocalDateTime slotStart = LocalDateTime.of(date, current);
                LocalDateTime slotEnd = slotStart.plusMinutes(30);

                if (slotStart.isAfter(now)) {
                    boolean collides = existingSessions.stream().anyMatch(
                            b -> slotStart.isBefore(b.end()) && slotEnd.isAfter(b.start())
                    );
                    if (!collides) {
                        availableSlots.add(new AvailableSlotDto(slotStart.format(ISO_DT), slotEnd.format(ISO_DT)));
                    }
                }
                current = current.plusMinutes(30);
            }
        }

        return availableSlots;
    }

    @Transactional
    public BookedAppointmentDto bookAppointment(UUID studentId, BookAppointmentDto input) {
        UUID advisorId = input.advisorId() != null ? input.advisorId() : resolveAssignedAdvisor(studentId);
        if (advisorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No advisor assigned. Please specify an advisor.");
        }

        LocalDateTime start = parseDateTime(input.startTime());
        LocalDateTime end = start.plusMinutes(30);

        if (start.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Cannot book appointment in the past");
        }

        Integer conflict = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advising_sessions
                WHERE (advisor_id = ? OR student_id = ?)
                  AND status IN ('PENDING', 'ACCEPTED', 'SCHEDULED', 'IN_PROGRESS')
                  AND scheduled_at < ? AND (ended_at > ? OR (ended_at IS NULL AND scheduled_at > ?))
                """, Integer.class, advisorId.toString(), studentId.toString(),
                Timestamp.valueOf(end), Timestamp.valueOf(start), Timestamp.valueOf(start.minusMinutes(30)));

        if (conflict != null && conflict > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot is no longer available");
        }

        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO advising_sessions (id, student_id, advisor_id, session_type, scheduled_at, ended_at,
                                               duration_minutes, status, agenda, summary, created_at, updated_at)
                VALUES (?, ?, ?, 'SCHEDULED', ?, ?, 30, 'PENDING', ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sessionId, studentId.toString(), advisorId.toString(),
                Timestamp.valueOf(start), Timestamp.valueOf(end), input.reason().strip());

        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'New Appointment Request', 'A student booked a 30-minute appointment.', FALSE)
                """, UUID.randomUUID().toString(), advisorId.toString());

        return getBookedAppointment(UUID.fromString(sessionId));
    }

    @Transactional
    public BookedAppointmentDto updateAppointmentStatus(UUID appointmentId, UUID advisorId, UpdateAppointmentStatusDto body) {
        String status = body.status().toUpperCase(Locale.ROOT);
        if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("status must be ACCEPTED or REJECTED");
        }
        if ("REJECTED".equals(status) && (body.reason() == null || body.reason().isBlank())) {
            throw new IllegalArgumentException("Reason is required when rejecting an appointment");
        }

        int updated = jdbc.update("""
                UPDATE advising_sessions
                SET status = ?, summary = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND advisor_id = ? AND status = 'PENDING'
                """, status, body.reason() != null ? body.reason().strip() : null,
                appointmentId.toString(), advisorId.toString());

        if (updated == 0) {
            boolean exists = !jdbc.queryForList("SELECT id FROM advising_sessions WHERE id = ?", appointmentId.toString()).isEmpty();
            if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Appointment is not pending or unauthorized");
        }

        BookedAppointmentDto appointment = getBookedAppointment(appointmentId);

        String notifTitle = "Appointment " + status;
        String notifMsg = "Your appointment has been " + status.toLowerCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', ?, ?, FALSE)
                """, UUID.randomUUID().toString(), appointment.studentId(), notifTitle, notifMsg);

        return appointment;
    }

    public List<AdvisorAppointmentDto> getAdvisorAppointments(UUID advisorId, String fromStr, String toStr) {
        LocalDateTime from = fromStr != null && !fromStr.isBlank() ? parseDateTime(fromStr) : LocalDateTime.now().minusDays(30);
        LocalDateTime to = toStr != null && !toStr.isBlank() ? parseDateTime(toStr) : LocalDateTime.now().plusDays(60);

        return jdbc.query("""
                SELECT s.id, s.student_id, u.full_name AS student_name, st.student_code,
                       s.scheduled_at, s.ended_at, s.duration_minutes, s.agenda, s.status, s.summary, s.created_at
                FROM advising_sessions s
                JOIN users u ON u.id = s.student_id
                LEFT JOIN students st ON st.user_id = s.student_id
                WHERE s.advisor_id = ? AND s.scheduled_at >= ? AND s.scheduled_at <= ?
                ORDER BY s.scheduled_at ASC
                """, (rs, rowNum) -> {
            LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
            int duration = rs.getInt("duration_minutes");
            if (duration <= 0) duration = 30;
            Timestamp endedTs = rs.getTimestamp("ended_at");
            LocalDateTime end = endedTs != null ? endedTs.toLocalDateTime() : start.plusMinutes(duration);
            Timestamp createdTs = rs.getTimestamp("created_at");

            return new AdvisorAppointmentDto(
                    rs.getString("id"),
                    rs.getString("student_id"),
                    rs.getString("student_name"),
                    rs.getString("student_code"),
                    start.format(ISO_DT),
                    end.format(ISO_DT),
                    rs.getString("agenda"),
                    rs.getString("status"),
                    rs.getString("summary"),
                    createdTs != null ? createdTs.toLocalDateTime().format(ISO_DT) : null
            );
        }, advisorId.toString(), Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    public List<BookedAppointmentDto> getStudentAppointments(UUID studentId) {
        return jdbc.query("""
                SELECT s.id, s.student_id, u.full_name AS student_name, st.student_code,
                       s.advisor_id, a.full_name AS advisor_name,
                       s.scheduled_at, s.ended_at, s.duration_minutes, s.agenda, s.status, s.summary, s.created_at
                FROM advising_sessions s
                JOIN users u ON u.id = s.student_id
                LEFT JOIN students st ON st.user_id = s.student_id
                JOIN users a ON a.id = s.advisor_id
                WHERE s.student_id = ?
                ORDER BY s.scheduled_at DESC
                """, (rs, rowNum) -> mapBookedAppointment(rs), studentId.toString());
    }

    @Transactional
    public BookedAppointmentDto cancelAppointment(UUID appointmentId, UUID actorId, CancelAppointmentDto input) {
        BookedAppointmentDto current = getBookedAppointment(appointmentId);
        if (!current.studentId().equals(actorId.toString()) && !current.advisorId().equals(actorId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized to cancel this appointment");
        }
        if ("CANCELLED".equals(current.status()) || "COMPLETED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Appointment is already closed");
        }

        String reason = input != null && input.reason() != null ? input.reason().strip() : "Cancelled by user";
        jdbc.update("""
                UPDATE advising_sessions
                SET status = 'CANCELLED', summary = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, reason, appointmentId.toString());

        String notifyRecipient = current.studentId().equals(actorId.toString()) ? current.advisorId() : current.studentId();
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'Appointment Cancelled', ?, FALSE)
                """, UUID.randomUUID().toString(), notifyRecipient, "An appointment was cancelled: " + reason);

        return getBookedAppointment(appointmentId);
    }

    @Transactional
    public BookedAppointmentDto rescheduleAppointment(UUID appointmentId, UUID actorId, RescheduleAppointmentDto input) {
        BookedAppointmentDto current = getBookedAppointment(appointmentId);
        if (!current.studentId().equals(actorId.toString()) && !current.advisorId().equals(actorId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unauthorized to reschedule this appointment");
        }
        if ("CANCELLED".equals(current.status()) || "COMPLETED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot reschedule a closed appointment");
        }

        LocalDateTime newStart = parseDateTime(input.startTime());
        LocalDateTime newEnd = newStart.plusMinutes(30);

        if (newStart.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Cannot reschedule to the past");
        }

        Integer conflict = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advising_sessions
                WHERE (advisor_id = ? OR student_id = ?)
                  AND id <> ?
                  AND status IN ('PENDING', 'ACCEPTED', 'SCHEDULED', 'IN_PROGRESS')
                  AND scheduled_at < ? AND (ended_at > ? OR (ended_at IS NULL AND scheduled_at > ?))
                """, Integer.class, current.advisorId(), current.studentId(), appointmentId.toString(),
                Timestamp.valueOf(newEnd), Timestamp.valueOf(newStart), Timestamp.valueOf(newStart.minusMinutes(30)));

        if (conflict != null && conflict > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target slot is not available");
        }

        String reason = input.reason() != null ? input.reason().strip() : current.reason();
        jdbc.update("""
                UPDATE advising_sessions
                SET scheduled_at = ?, ended_at = ?, agenda = ?, status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, Timestamp.valueOf(newStart), Timestamp.valueOf(newEnd), reason, appointmentId.toString());

        String notifyRecipient = current.studentId().equals(actorId.toString()) ? current.advisorId() : current.studentId();
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', 'Appointment Rescheduled', ?, FALSE)
                """, UUID.randomUUID().toString(), notifyRecipient, "An appointment was rescheduled to " + newStart);

        return getBookedAppointment(appointmentId);
    }

    public BookedAppointmentDto getBookedAppointment(UUID appointmentId) {
        List<BookedAppointmentDto> list = jdbc.query("""
                SELECT s.id, s.student_id, u.full_name AS student_name, st.student_code,
                       s.advisor_id, a.full_name AS advisor_name,
                       s.scheduled_at, s.ended_at, s.duration_minutes, s.agenda, s.status, s.summary, s.created_at
                FROM advising_sessions s
                JOIN users u ON u.id = s.student_id
                LEFT JOIN students st ON st.user_id = s.student_id
                JOIN users a ON a.id = s.advisor_id
                WHERE s.id = ?
                """, (rs, rowNum) -> mapBookedAppointment(rs), appointmentId.toString());

        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        }
        return list.get(0);
    }

    private BookedAppointmentDto mapBookedAppointment(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
        int duration = rs.getInt("duration_minutes");
        if (duration <= 0) duration = 30;
        Timestamp endedTs = rs.getTimestamp("ended_at");
        LocalDateTime end = endedTs != null ? endedTs.toLocalDateTime() : start.plusMinutes(duration);
        Timestamp createdTs = rs.getTimestamp("created_at");

        return new BookedAppointmentDto(
                rs.getString("id"),
                rs.getString("student_id"),
                rs.getString("student_name"),
                rs.getString("student_code"),
                rs.getString("advisor_id"),
                rs.getString("advisor_name"),
                start.format(ISO_DT),
                end.format(ISO_DT),
                rs.getString("agenda"),
                rs.getString("status"),
                rs.getString("summary"),
                createdTs != null ? createdTs.toLocalDateTime().format(ISO_DT) : null
        );
    }

    private UUID resolveAssignedAdvisor(UUID studentId) {
        List<String> list = jdbc.query("""
                SELECT advisor_id FROM student_advisor_assignments
                WHERE student_id = ? AND status = 'ACTIVE' AND end_date IS NULL
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), studentId.toString());
        if (!list.isEmpty()) {
            return UUID.fromString(list.get(0));
        }

        List<String> anyAdvisor = jdbc.query("""
                SELECT user_id FROM advisors LIMIT 1
                """, (rs, rowNum) -> rs.getString(1));
        if (!anyAdvisor.isEmpty()) {
            return UUID.fromString(anyAdvisor.get(0));
        }
        return null;
    }

    private int parseDayOfWeek(String dow) {
        if (dow == null || dow.isBlank()) return 1;
        try {
            return Integer.parseInt(dow.trim());
        } catch (NumberFormatException ignored) {}
        try {
            return DayOfWeek.valueOf(dow.trim().toUpperCase(Locale.ROOT)).getValue();
        } catch (IllegalArgumentException ignored) {}
        return 1;
    }

    private LocalDateTime parseDateTime(String str) {
        if (str.endsWith("Z")) {
            return java.time.Instant.parse(str).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (str.contains("+") || (str.lastIndexOf('-') > 10)) {
            return java.time.OffsetDateTime.parse(str).toLocalDateTime();
        }
        return LocalDateTime.parse(str);
    }

    private record BookedInterval(LocalDateTime start, LocalDateTime end) {}
}
