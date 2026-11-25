package vn.edu.fpt.faaps.appointment.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;

@Service
public class AdvisorProactiveAdvisingService {
    private static final Set<String> BOOKING_TYPES = Set.of("SCHEDULED", "VIRTUAL", "WALK_IN");

    private final JdbcTemplate jdbc;
    private final EmailService emailService;

    public AdvisorProactiveAdvisingService(JdbcTemplate jdbc, EmailService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    @Transactional
    public ProactiveSessionResult createProactiveAppointment(UUID advisorId, ProactiveAppointmentRequest request) {
        UserContact student = requireStudent(request.studentId());
        String advisorName = advisorName(advisorId);
        String type = normalizeBookingType(request.sessionType());
        LocalDateTime scheduledAt = parseScheduledAt(request.scheduledAt());
        String sessionId = UUID.randomUUID().toString();
        String agenda = request.agenda().strip();

        jdbc.update("""
                INSERT INTO advising_sessions
                (id, student_id, advisor_id, session_type, scheduled_at, location, meeting_link, agenda, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
                """,
                sessionId,
                student.id(),
                advisorId.toString(),
                type,
                Timestamp.valueOf(scheduledAt),
                trimOrNull(request.location()),
                trimOrNull(request.meetingLink()),
                agenda);

        notifyStudent(student.id(),
                "New Advising Appointment Scheduled",
                advisorName + " scheduled an advising session (" + type + ") on " + scheduledAt + ": " + agenda,
                "/student/appointments");

        String details = "Session type: " + type + "\nScheduled time: " + scheduledAt
                + (request.location() != null && !request.location().isBlank() ? "\nLocation: " + request.location().strip() : "")
                + (request.meetingLink() != null && !request.meetingLink().isBlank() ? "\nMeeting link: " + request.meetingLink().strip() : "")
                + "\nAgenda: " + agenda;
        emailService.sendAdvisingMessage(student.email(), student.fullName(), advisorName, "Advising Appointment Scheduled", details);

        return new ProactiveSessionResult(sessionId, student.id(), type, "SCHEDULED", scheduledAt);
    }

    @Transactional
    public ProactiveSessionResult sendEmailAdvising(UUID advisorId, EmailAdvisingRequest request) {
        UserContact student = requireStudent(request.studentId());
        String advisorName = advisorName(advisorId);
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String subject = request.subject().strip();
        String body = request.message().strip();

        jdbc.update("""
                INSERT INTO advising_sessions
                (id, student_id, advisor_id, session_type, scheduled_at, started_at, ended_at, agenda, summary, status)
                VALUES (?, ?, ?, 'EMAIL', ?, ?, ?, ?, ?, 'COMPLETED')
                """,
                sessionId,
                student.id(),
                advisorId.toString(),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                subject,
                body);

        notifyStudent(student.id(),
                "Advising Email Sent: " + subject,
                advisorName + " sent you academic advising guidance via email.",
                "/student/appointments");

        emailService.sendAdvisingMessage(student.email(), student.fullName(), advisorName, subject, body);
        return new ProactiveSessionResult(sessionId, student.id(), "EMAIL", "COMPLETED", now);
    }

    private UserContact requireStudent(UUID studentId) {
        if (studentId == null) {
            throw new IllegalArgumentException("studentId is required");
        }
        List<UserContact> rows = jdbc.query("""
                SELECT u.id, u.full_name, u.email
                FROM users u JOIN students s ON s.user_id = u.id
                WHERE u.id = ?
                """, (rs, row) -> new UserContact(rs.getString("id"), rs.getString("full_name"), rs.getString("email")),
                studentId.toString());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        }
        return rows.get(0);
    }

    private String advisorName(UUID advisorId) {
        List<String> rows = jdbc.query(
                "SELECT full_name FROM users WHERE id = ?",
                (rs, row) -> rs.getString(1),
                advisorId.toString());
        return rows.isEmpty() ? "Academic Advisor" : rows.get(0);
    }

    private void notifyStudent(String studentId, String title, String message, String actionUrl) {
        jdbc.update("""
                INSERT INTO notifications
                (id, recipient_user_id, notification_type, title, message, action_url, related_student_id)
                VALUES (?, ?, 'REMINDER', ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                studentId,
                title,
                message,
                actionUrl,
                studentId);
    }

    private static String normalizeBookingType(String raw) {
        String type = raw == null || raw.isBlank() ? "VIRTUAL" : raw.strip().toUpperCase(Locale.ROOT);
        if (!BOOKING_TYPES.contains(type)) {
            throw new IllegalArgumentException("sessionType must be SCHEDULED, VIRTUAL, or WALK_IN");
        }
        return type;
    }

    private static LocalDateTime parseScheduledAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("scheduledAt is required");
        }
        try {
            return LocalDateTime.parse(raw.strip().replace(" ", "T"));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("scheduledAt must be a valid ISO date-time", ex);
        }
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record UserContact(String id, String fullName, String email) {}

    public record ProactiveAppointmentRequest(
            UUID studentId,
            String sessionType,
            String scheduledAt,
            String location,
            String meetingLink,
            String agenda) {}

    public record EmailAdvisingRequest(UUID studentId, String subject, String message) {}

    public record ProactiveSessionResult(
            String sessionId,
            String studentId,
            String sessionType,
            String status,
            LocalDateTime scheduledAt) {}
}
