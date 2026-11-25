package vn.edu.fpt.faaps.appointment.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.CancelAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.UpdateAppointmentStatusDto;
import vn.edu.fpt.faaps.appointment.repository.AppointmentReadRepository;

class AppointmentStatusHelper {
    private static final Logger log = LoggerFactory.getLogger(AppointmentStatusHelper.class);
    private static final List<String> ALLOWED_STATUSES =
            List.of("ACCEPTED", "REJECTED", "CANCELLED", "COMPLETED", "NO_SHOW");

    private final JdbcTemplate jdbc;
    private final AppointmentReadRepository reads;
    private final AppointmentBookingHelper booking;

    AppointmentStatusHelper(JdbcTemplate jdbc, AppointmentReadRepository reads, AppointmentBookingHelper booking) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.booking = booking;
    }

    BookedAppointmentDto updateAppointmentStatus(UUID appointmentId, UUID advisorId, UpdateAppointmentStatusDto body) {
        String status = body.status() == null ? "" : body.status().strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                    "status must be ACCEPTED, REJECTED, CANCELLED, COMPLETED, or NO_SHOW");
        }
        String consultationSummary = body.consultationSummary() != null && !body.consultationSummary().isBlank()
                ? body.consultationSummary().strip() : null;
        String reason = body.reason() != null && !body.reason().isBlank()
                ? body.reason().strip() : null;
        String noteOrSummary = consultationSummary != null ? consultationSummary : reason;

        if (("REJECTED".equals(status) || "CANCELLED".equals(status)) && noteOrSummary == null) {
            throw new IllegalArgumentException("Reason is required when rejecting or cancelling an appointment");
        }
        if ("COMPLETED".equals(status) && noteOrSummary == null) {
            throw new IllegalArgumentException("consultationSummary is required when completing an appointment");
        }

        int updated = applyStatusUpdate(appointmentId, advisorId, status, reason, noteOrSummary);
        if (updated == 0) {
            boolean exists = !jdbc.queryForList(
                    "SELECT id FROM advising_sessions WHERE id = ?", appointmentId.toString()).isEmpty();
            if (!exists) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Appointment cannot transition to " + status + " or unauthorized");
        }

        BookedAppointmentDto appointment = reads.appointment(appointmentId);
        String notifTitle = "Appointment " + status;
        String notifMsg = "Your appointment has been marked as "
                + status.toLowerCase(Locale.ROOT).replace('_', ' ');
        booking.notifyUser(appointment.studentId(), notifTitle, notifMsg);
        return appointment;
    }

    BookedAppointmentDto cancelAppointment(UUID appointmentId, UUID actorId, CancelAppointmentDto input) {
        BookedAppointmentDto current = reads.appointment(appointmentId);
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

        String notifyRecipient = current.studentId().equals(actorId.toString())
                ? current.advisorId()
                : current.studentId();
        booking.notifyUser(notifyRecipient, "Appointment Cancelled", "An appointment was cancelled: " + reason);
        return reads.appointment(appointmentId);
    }

    private int applyStatusUpdate(UUID appointmentId, UUID advisorId, String status,
            String reason, String noteOrSummary) {
        String idStr = appointmentId.toString();
        String advisorStr = advisorId.toString();
        if ("ACCEPTED".equals(status) || "REJECTED".equals(status)) {
            return jdbc.update("""
                    UPDATE advising_sessions
                    SET status = ?, summary = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND advisor_id = ? AND status = 'PENDING'
                    """, status, reason != null ? reason : noteOrSummary, idStr, advisorStr);
        }
        if ("COMPLETED".equals(status)) {
            return markCompleted(idStr, advisorStr, reason, noteOrSummary);
        }
        if ("NO_SHOW".equals(status)) {
            return jdbc.update("""
                    UPDATE advising_sessions
                    SET status = 'NO_SHOW', summary = COALESCE(?, summary), updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND advisor_id = ?
                      AND status IN ('ACCEPTED', 'SCHEDULED', 'IN_PROGRESS', 'PENDING', 'NO_SHOW')
                    """, noteOrSummary, idStr, advisorStr);
        }
        return jdbc.update("""
                UPDATE advising_sessions
                SET status = 'CANCELLED', summary = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND advisor_id = ?
                  AND status NOT IN ('CANCELLED', 'COMPLETED')
                """, noteOrSummary, idStr, advisorStr);
    }

    private int markCompleted(String idStr, String advisorStr, String reason, String noteOrSummary) {
        try {
            return jdbc.update("""
                    UPDATE advising_sessions
                    SET status = 'COMPLETED',
                        consultation_summary = COALESCE(?, consultation_summary),
                        summary = COALESCE(?, summary),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND advisor_id = ?
                      AND status IN ('ACCEPTED', 'SCHEDULED', 'IN_PROGRESS', 'PENDING', 'COMPLETED')
                    """, noteOrSummary, reason, idStr, advisorStr);
        } catch (Exception ex) {
            log.debug("Falling back to legacy advising_sessions completion update: {}", ex.getMessage());
            return jdbc.update("""
                    UPDATE advising_sessions
                    SET status = 'COMPLETED', summary = COALESCE(?, summary), updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND advisor_id = ?
                      AND status IN ('ACCEPTED', 'SCHEDULED', 'IN_PROGRESS', 'PENDING', 'COMPLETED')
                    """, noteOrSummary, idStr, advisorStr);
        }
    }
}
