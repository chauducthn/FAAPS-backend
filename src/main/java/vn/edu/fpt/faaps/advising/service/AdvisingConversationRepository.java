package vn.edu.fpt.faaps.advising.service;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService.Message;
import vn.edu.fpt.faaps.advising.service.AdvisingConversationService.Request;
import vn.edu.fpt.faaps.advising.service.AdvisingRequestMapper.Viewer;

/**
 * Advising requests are an open pool: every advisor sees every request, while a student sees only their own.
 * Queries fall back to a schema without major groups or appointment links, logging why.
 */
class AdvisingConversationRepository {
    private static final Logger log = LoggerFactory.getLogger(AdvisingConversationRepository.class);
    private static final String ENRICHED_SELECT = """
            SELECT r.id,r.student_id,s.full_name AS student_name,st.student_code,
                   m.code AS student_major_code,m.name AS student_major_name,
                   m.major_group_id AS student_major_group_id,mg.code AS student_major_group_code,
                   mg.name AS student_major_group_name,r.advisor_id,a.full_name AS advisor_name,r.study_plan_id,
                   (SELECT ses.id FROM advising_sessions ses WHERE ses.advising_request_id=r.id
                    ORDER BY ses.created_at DESC LIMIT 1) AS linked_appointment_id,
                   r.title,r.description,r.request_type,r.status,r.resolution_summary,
                   r.resolved_at,r.created_at,r.updated_at
            FROM advising_requests r JOIN users s ON s.id=r.student_id
            LEFT JOIN students st ON st.user_id=r.student_id
            LEFT JOIN majors m ON m.id=st.major_id
            LEFT JOIN major_groups mg ON mg.id=m.major_group_id
            LEFT JOIN users a ON a.id=r.advisor_id
            """;
    private static final String BASIC_SELECT = """
            SELECT r.id,r.student_id,s.full_name AS student_name,st.student_code,
                   r.advisor_id,a.full_name AS advisor_name,r.study_plan_id,
                   r.title,r.description,r.request_type,r.status,r.resolution_summary,
                   r.resolved_at,r.created_at,r.updated_at
            FROM advising_requests r JOIN users s ON s.id=r.student_id
            LEFT JOIN students st ON st.user_id=r.student_id
            LEFT JOIN users a ON a.id=r.advisor_id
            """;
    private static final String ORDER = " ORDER BY r.created_at DESC,r.id DESC";
    private final JdbcTemplate jdbc;

    AdvisingConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Viewer viewer(UUID actorId, boolean advisor) {
        if (!advisor) {
            return Viewer.NONE;
        }
        String groupId;
        try {
            groupId = jdbc.query("SELECT major_group_id FROM advisors WHERE user_id=?",
                    (rs, row) -> rs.getString(1), actorId.toString()).stream().findFirst().orElse(null);
        } catch (BadSqlGrammarException ex) {
            log.debug("Advisor major group unavailable, request affinity is not ranked: {}", ex.getMessage());
            groupId = null;
        }
        var participated = new HashSet<>(jdbc.query(
                "SELECT DISTINCT advising_request_id FROM advising_messages WHERE sender_user_id=?",
                (rs, row) -> rs.getString(1), actorId.toString()));
        return new Viewer(actorId, groupId, participated);
    }

    List<Request> findAll(UUID actorId, boolean advisor, Viewer viewer) {
        String where = advisor ? "" : " WHERE r.student_id=?";
        Object[] args = advisor ? new Object[0] : new Object[] { actorId.toString() };
        try {
            return jdbc.query(ENRICHED_SELECT + where + ORDER, (rs, row) -> AdvisingRequestMapper.enriched(rs, viewer), args);
        } catch (BadSqlGrammarException ex) {
            log.debug("Falling back to advising_requests list query without major groups: {}", ex.getMessage());
            return jdbc.query(BASIC_SELECT + where + ORDER, (rs, row) -> AdvisingRequestMapper.basic(rs, viewer), args);
        }
    }

    Request findOne(UUID actorId, boolean advisor, UUID requestId, Viewer viewer) {
        String where = advisor ? " WHERE r.id=?" : " WHERE r.id=? AND r.student_id=?";
        Object[] args = advisor ? new Object[] { requestId.toString() }
                : new Object[] { requestId.toString(), actorId.toString() };
        List<Request> rows;
        try {
            rows = jdbc.query(ENRICHED_SELECT + where, (rs, row) -> AdvisingRequestMapper.enriched(rs, viewer), args);
        } catch (BadSqlGrammarException ex) {
            log.debug("Falling back to advising_requests detail query without major groups: {}", ex.getMessage());
            rows = jdbc.query(BASIC_SELECT + where, (rs, row) -> AdvisingRequestMapper.basic(rs, viewer), args);
        }
        return rows.stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Advising request not found"));
    }

    List<Message> findMessages(UUID requestId, boolean advisor) {
        String visibility = (advisor ? "" : " AND m.is_internal=FALSE") + " ORDER BY m.created_at,m.id";
        return jdbc.query("""
                SELECT m.id,m.sender_user_id,u.full_name AS sender_name,r.code AS sender_role,
                       m.content,m.is_internal,m.created_at
                FROM advising_messages m JOIN users u ON u.id=m.sender_user_id
                LEFT JOIN roles r ON r.id=u.role_id
                WHERE m.advising_request_id=?
                """ + visibility,
                (rs, row) -> new Message(rs.getString("id"), rs.getString("sender_user_id"),
                        rs.getString("sender_name"), rs.getString("sender_role"), rs.getString("content"),
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

    /** The first advisor to reply claims an unowned request; later advisors may still join the thread. */
    void claimAndMarkInProgress(UUID requestId, UUID advisorId) {
        jdbc.update("""
                UPDATE advising_requests
                SET advisor_id=COALESCE(advisor_id, ?),
                    status=CASE WHEN status='PENDING' THEN 'IN_PROGRESS' ELSE status END,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, advisorId.toString(), requestId.toString());
    }

    void touchRequest(UUID requestId) {
        jdbc.update("UPDATE advising_requests SET updated_at=CURRENT_TIMESTAMP WHERE id=?", requestId.toString());
    }

    /** A null advisor (a student cancelling) leaves the owner untouched; a blank summary keeps the stored one. */
    void updateStatus(UUID requestId, UUID advisorId, String status, String summary) {
        jdbc.update("""
                UPDATE advising_requests
                SET advisor_id=COALESCE(advisor_id, ?), status=?,
                    resolution_summary=COALESCE(?, resolution_summary),
                    resolved_at=CASE WHEN ? IN ('RESOLVED','REJECTED') THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, advisorId == null ? null : advisorId.toString(), status,
                summary == null || summary.isBlank() ? null : summary.strip(), status, requestId.toString());
    }

    void notifyUser(String recipient, String title, String message) {
        jdbc.update("""
                INSERT INTO notifications(id,recipient_user_id,notification_type,title,message,is_read)
                VALUES (?,?,'INFO',?,?,FALSE)
                """, UUID.randomUUID().toString(), recipient, title, message);
    }
}
