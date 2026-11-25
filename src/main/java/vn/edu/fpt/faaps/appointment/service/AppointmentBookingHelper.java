package vn.edu.fpt.faaps.appointment.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.RescheduleAppointmentDto;
import vn.edu.fpt.faaps.appointment.repository.AppointmentReadRepository;

class AppointmentBookingHelper {
    private final JdbcTemplate jdbc;
    private final AppointmentReadRepository reads;

    AppointmentBookingHelper(JdbcTemplate jdbc, AppointmentReadRepository reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    BookedAppointmentDto bookAppointment(UUID studentId, BookAppointmentDto input) {
        UUID advisorId = input.advisorId() != null ? input.advisorId() : reads.assignedAdvisor(studentId);
        if (advisorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No advisor assigned. Please specify an advisor.");
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

        notifyUser(advisorId.toString(), "New Appointment Request",
                "A student booked a 30-minute appointment.");
        return reads.appointment(UUID.fromString(sessionId));
    }

    BookedAppointmentDto rescheduleAppointment(UUID appointmentId, UUID actorId, RescheduleAppointmentDto input) {
        BookedAppointmentDto current = reads.appointment(appointmentId);
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
                Timestamp.valueOf(newEnd), Timestamp.valueOf(newStart),
                Timestamp.valueOf(newStart.minusMinutes(30)));
        if (conflict != null && conflict > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target slot is not available");
        }

        String reason = input.reason() != null ? input.reason().strip() : current.reason();
        jdbc.update("""
                UPDATE advising_sessions
                SET scheduled_at = ?, ended_at = ?, agenda = ?, status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, Timestamp.valueOf(newStart), Timestamp.valueOf(newEnd), reason, appointmentId.toString());

        String notifyRecipient = current.studentId().equals(actorId.toString())
                ? current.advisorId()
                : current.studentId();
        notifyUser(notifyRecipient, "Appointment Rescheduled",
                "An appointment was rescheduled to " + newStart);
        return reads.appointment(appointmentId);
    }

    void notifyUser(String recipientUserId, String title, String message) {
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, notification_type, title, message, is_read)
                VALUES (?, ?, 'INFO', ?, ?, FALSE)
                """, UUID.randomUUID().toString(), recipientUserId, title, message);
    }

    LocalDateTime parseRangeBoundary(String value, boolean endOfDay) {
        if (value.length() == 10) {
            LocalDate date = LocalDate.parse(value);
            return endOfDay ? date.plusDays(1).atStartOfDay().minusNanos(1) : date.atStartOfDay();
        }
        return parseDateTime(value);
    }

    private LocalDateTime parseDateTime(String str) {
        if (str.endsWith("Z")) {
            return Instant.parse(str).atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (str.contains("+") || (str.lastIndexOf('-') > 10)) {
            return OffsetDateTime.parse(str).toLocalDateTime();
        }
        return LocalDateTime.parse(str);
    }
}
