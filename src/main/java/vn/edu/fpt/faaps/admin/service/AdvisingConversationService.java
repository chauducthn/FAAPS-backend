package vn.edu.fpt.faaps.admin.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdvisingConversationService {
    private final JdbcTemplate jdbc;

    public AdvisingConversationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Request> list(UUID actorId, boolean advisor) {
        String filter = advisor ? "r.advisor_id=?" : "r.student_id=?";
        return jdbc.query("""
                SELECT r.id,r.student_id,s.full_name AS student_name,r.advisor_id,a.full_name AS advisor_name,
                       r.title,r.description,r.request_type,r.status,r.resolution_summary,r.created_at,r.updated_at
                FROM advising_requests r JOIN users s ON s.id=r.student_id
                LEFT JOIN users a ON a.id=r.advisor_id WHERE
                """ + filter + " ORDER BY r.created_at DESC,r.id DESC",
                (rs, row) -> mapRequest(rs), actorId.toString());
    }

    public Detail detail(UUID actorId, boolean advisor, UUID requestId) {
        Request request = request(actorId, advisor, requestId);
        List<Message> messages = jdbc.query("""
                SELECT m.id,m.sender_user_id,u.full_name AS sender_name,m.content,m.is_internal,m.created_at
                FROM advising_messages m JOIN users u ON u.id=m.sender_user_id
                WHERE m.advising_request_id=?
                """ + (advisor ? "" : " AND m.is_internal=FALSE") + " ORDER BY m.created_at,m.id",
                (rs, row) -> new Message(rs.getString("id"), rs.getString("sender_user_id"),
                rs.getString("sender_name"), rs.getString("content"), rs.getBoolean("is_internal"),
                rs.getTimestamp("created_at").toLocalDateTime()), requestId.toString());
        return new Detail(request, messages);
    }

    @Transactional
    public Detail addMessage(UUID actorId, boolean advisor, UUID requestId, String content, boolean internal) {
        if (content == null || content.isBlank() || content.length() > 10_000)
            throw new IllegalArgumentException("Message must have 1 to 10000 characters");
        lockRequest(requestId);
        Request request = request(actorId, advisor, requestId);
        if ("RESOLVED".equals(request.status()) || "CANCELLED".equals(request.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
        jdbc.update("""
                INSERT INTO advising_messages(id,advising_request_id,sender_user_id,content,is_internal)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID().toString(), requestId.toString(), actorId.toString(), content.strip(), advisor && internal);
        if (advisor && !internal) {
            jdbc.update("UPDATE advising_requests SET status='IN_PROGRESS',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                    requestId.toString());
            notifyUser(request.studentId(), "Advisor replied", "Your academic advisor replied to your request.");
        } else {
            jdbc.update("UPDATE advising_requests SET updated_at=CURRENT_TIMESTAMP WHERE id=?", requestId.toString());
            if (!advisor && request.advisorId() != null)
                notifyUser(request.advisorId(), "Student replied", "A student replied to an advising request.");
        }
        return detail(actorId, advisor, requestId);
    }

    @Transactional
    public Detail changeStatus(UUID actorId, boolean advisor, UUID requestId, String status, String summary) {
        lockRequest(requestId);
        Request request = request(actorId, advisor, requestId);
        if (advisor) {
            if (!"IN_PROGRESS".equals(status) && !"RESOLVED".equals(status))
                throw new IllegalArgumentException("Advisor status must be IN_PROGRESS or RESOLVED");
            if ("RESOLVED".equals(request.status()) || "CANCELLED".equals(request.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
            if ("RESOLVED".equals(status) && (summary == null || summary.isBlank()))
                throw new IllegalArgumentException("Resolution summary is required");
            if (summary != null && summary.length() > 10_000)
                throw new IllegalArgumentException("Resolution summary is too long");
        } else {
            if (!"CANCELLED".equals(status)) throw new IllegalArgumentException("Student can only cancel a request");
            if ("RESOLVED".equals(request.status()) || "CANCELLED".equals(request.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is closed");
        }
        jdbc.update("""
                UPDATE advising_requests SET status=?,resolution_summary=?,
                  resolved_at=CASE WHEN ?='RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                  updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, status, summary, status, requestId.toString());
        if (advisor) notifyUser(request.studentId(), "Advising request updated", "Your advising request status changed.");
        else if (request.advisorId() != null)
            notifyUser(request.advisorId(), "Advising request cancelled", "A student cancelled an advising request.");
        return detail(actorId, advisor, requestId);
    }

    private Request request(UUID actorId, boolean advisor, UUID requestId) {
        String filter = advisor ? "r.advisor_id=?" : "r.student_id=?";
        return jdbc.query("""
                SELECT r.id,r.student_id,s.full_name AS student_name,r.advisor_id,a.full_name AS advisor_name,
                       r.title,r.description,r.request_type,r.status,r.resolution_summary,r.created_at,r.updated_at
                FROM advising_requests r JOIN users s ON s.id=r.student_id
                LEFT JOIN users a ON a.id=r.advisor_id WHERE r.id=? AND
                """ + filter, (rs, row) -> mapRequest(rs), requestId.toString(), actorId.toString())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Advising request not found"));
    }

    private void lockRequest(UUID requestId) {
        jdbc.query("SELECT id FROM advising_requests WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), requestId.toString());
    }

    private Request mapRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Request(rs.getString("id"), rs.getString("student_id"), rs.getString("student_name"),
                rs.getString("advisor_id"), rs.getString("advisor_name"), rs.getString("title"),
                rs.getString("description"), rs.getString("request_type"), rs.getString("status"),
                rs.getString("resolution_summary"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private void notifyUser(String recipient, String title, String message) {
        jdbc.update("""
                INSERT INTO notifications(id,recipient_user_id,notification_type,title,message,is_read)
                VALUES (?,?, 'INFO',?,?,FALSE)
                """, UUID.randomUUID().toString(), recipient, title, message);
    }

    public record Request(String id, String studentId, String studentName, String advisorId,
            String advisorName, String title, String description, String requestType, String status,
            String resolutionSummary, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Message(String id, String senderId, String senderName, String content,
            boolean internal, LocalDateTime createdAt) {}
    public record Detail(Request request, List<Message> messages) {}
}
