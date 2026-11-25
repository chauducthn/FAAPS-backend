package vn.edu.fpt.faaps.advising.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService.Message;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService.Request;

class AdvisingConversationRepository {
    private static final Logger log = LoggerFactory.getLogger(AdvisingConversationRepository.class);
    private final JdbcTemplate jdbc;

    AdvisingConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Request> findAll(UUID actorId, boolean advisor) {
        String filter = advisor ? "r.advisor_id=?" : "r.student_id=?";
        try {
            return jdbc.query("""
                    SELECT r.id,r.student_id,s.full_name AS student_name,st.student_code,
                           r.advisor_id,a.full_name AS advisor_name,r.study_plan_id,
                           r.title,r.description,r.request_type,r.status,r.resolution_summary,
                           r.resolved_at,r.created_at,r.updated_at
                    FROM advising_requests r JOIN users s ON s.id=r.student_id
                    LEFT JOIN students st ON st.user_id=r.student_id
                    LEFT JOIN users a ON a.id=r.advisor_id WHERE
                    """ + filter + " ORDER BY r.created_at DESC,r.id DESC",
                    (rs, row) -> mapFullRequest(rs), actorId.toString());
        } catch (Exception ex) {
            log.debug("Falling back to legacy advising_requests list query: {}", ex.getMessage());
            return jdbc.query("""
                    SELECT r.id,r.student_id,s.full_name AS student_name,r.advisor_id,a.full_name AS advisor_name,
                           r.title,r.description,r.request_type,r.status,r.resolution_summary,r.created_at,r.updated_at
                    FROM advising_requests r JOIN users s ON s.id=r.student_id
                    LEFT JOIN users a ON a.id=r.advisor_id WHERE
                    """ + filter + " ORDER BY r.created_at DESC,r.id DESC",
                    (rs, row) -> mapRequest(rs), actorId.toString());
        }
    }

    Request findOne(UUID actorId, boolean advisor, UUID requestId) {
        String filter = advisor ? "r.advisor_id=?" : "r.student_id=?";
        try {
            return jdbc.query("""
                    SELECT r.id,r.student_id,s.full_name AS student_name,st.student_code,
                           r.advisor_id,a.full_name AS advisor_name,r.study_plan_id,
                           r.title,r.description,r.request_type,r.status,r.resolution_summary,
                           r.resolved_at,r.created_at,r.updated_at
                    FROM advising_requests r JOIN users s ON s.id=r.student_id
                    LEFT JOIN students st ON st.user_id=r.student_id
                    LEFT JOIN users a ON a.id=r.advisor_id WHERE r.id=? AND
                    """ + filter, (rs, row) -> mapFullRequest(rs), requestId.toString(), actorId.toString())
                    .stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Advising request not found"));
        } catch (ResponseStatusException notFound) {
            throw notFound;
        } catch (Exception ex) {
            log.debug("Falling back to legacy advising_requests detail query: {}", ex.getMessage());
            return jdbc.query("""
                    SELECT r.id,r.student_id,s.full_name AS student_name,r.advisor_id,a.full_name AS advisor_name,
                           r.title,r.description,r.request_type,r.status,r.resolution_summary,r.created_at,r.updated_at
                    FROM advising_requests r JOIN users s ON s.id=r.student_id
                    LEFT JOIN users a ON a.id=r.advisor_id WHERE r.id=? AND
                    """ + filter, (rs, row) -> mapRequest(rs), requestId.toString(), actorId.toString())
                    .stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Advising request not found"));
        }
    }

    List<Message> findMessages(UUID requestId, boolean advisor) {
        return jdbc.query("""
                SELECT m.id,m.sender_user_id,u.full_name AS sender_name,m.content,m.is_internal,m.created_at
                FROM advising_messages m JOIN users u ON u.id=m.sender_user_id
                WHERE m.advising_request_id=?
                """ + (advisor ? "" : " AND m.is_internal=FALSE") + " ORDER BY m.created_at,m.id",
                (rs, row) -> new Message(
                        rs.getString("id"), rs.getString("sender_user_id"),
                        rs.getString("sender_name"), rs.getString("content"),
                        rs.getBoolean("is_internal"), rs.getTimestamp("created_at").toLocalDateTime()),
                requestId.toString());
    }

    void lockRequest(UUID requestId) {
        jdbc.query("SELECT id FROM advising_requests WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), requestId.toString());
    }

    void insertMessage(UUID requestId, UUID actorId, String content, boolean internal) {
        jdbc.update("""
                INSERT INTO advising_messages(id,advising_request_id,sender_user_id,content,is_internal)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID().toString(), requestId.toString(), actorId.toString(), content, internal);
    }

    void markInProgressIfPending(UUID requestId) {
        jdbc.update("""
                UPDATE advising_requests SET status='IN_PROGRESS',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING'
                """, requestId.toString());
    }

    void touchRequest(UUID requestId) {
        jdbc.update("UPDATE advising_requests SET updated_at=CURRENT_TIMESTAMP WHERE id=?", requestId.toString());
    }

    void updateStatus(UUID requestId, String status, String summary) {
        jdbc.update("""
                UPDATE advising_requests SET status=?,resolution_summary=?,
                  resolved_at=CASE WHEN ?='RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                  updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, status, summary, status, requestId.toString());
    }

    void notifyUser(String recipient, String title, String message) {
        jdbc.update("""
                INSERT INTO notifications(id,recipient_user_id,notification_type,title,message,is_read)
                VALUES (?,?,'INFO',?,?,FALSE)
                """, UUID.randomUUID().toString(), recipient, title, message);
    }

    private Request mapFullRequest(ResultSet rs) throws SQLException {
        var resolvedTs = rs.getTimestamp("resolved_at");
        var createdTs = rs.getTimestamp("created_at");
        var updatedTs = rs.getTimestamp("updated_at");
        return new Request(
                rs.getString("id"), rs.getString("student_id"), rs.getString("student_name"),
                rs.getString("student_code"), rs.getString("advisor_id"), rs.getString("advisor_name"),
                rs.getString("study_plan_id"), rs.getString("title"), rs.getString("description"),
                rs.getString("request_type"), rs.getString("status"), rs.getString("resolution_summary"),
                resolvedTs != null ? resolvedTs.toLocalDateTime() : null,
                createdTs != null ? createdTs.toLocalDateTime() : null,
                updatedTs != null ? updatedTs.toLocalDateTime() : null);
    }

    private Request mapRequest(ResultSet rs) throws SQLException {
        var createdTs = rs.getTimestamp("created_at");
        var updatedTs = rs.getTimestamp("updated_at");
        return new Request(
                rs.getString("id"), rs.getString("student_id"), rs.getString("student_name"),
                null, rs.getString("advisor_id"), rs.getString("advisor_name"), null,
                rs.getString("title"), rs.getString("description"), rs.getString("request_type"),
                rs.getString("status"), rs.getString("resolution_summary"), null,
                createdTs != null ? createdTs.toLocalDateTime() : null,
                updatedTs != null ? updatedTs.toLocalDateTime() : null);
    }
}
