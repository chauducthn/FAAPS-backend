package vn.edu.fpt.faaps.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAiReportService {
    private final JdbcTemplate jdbc;

    public AdminAiReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page list(String status, String search, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            if (!List.of("PENDING", "RESOLVED", "DISMISSED").contains(status.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("status is invalid");
            }
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> values = new ArrayList<>();
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            where.append(" AND report.status = ?");
            values.add(status.toUpperCase(Locale.ROOT));
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(report.id) LIKE ? OR LOWER(student.full_name) LIKE ? OR LOWER(s.student_code) LIKE ?)");
            values.add(term);
            values.add(term);
            values.add(term);
        }
        String from = " FROM ai_response_reports report JOIN users student ON student.id = report.student_id LEFT JOIN students s ON s.user_id = report.student_id";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, values.toArray());
        values.add(size);
        values.add(page * size);
        List<Summary> items = jdbc.query("""
                SELECT report.id, report.conversation_id, report.student_id,
                       student.full_name AS student_name, s.student_code,
                       report.reason, report.status, report.created_at,
                       (SELECT COUNT(*) FROM ai_reported_messages item WHERE item.report_id = report.id) AS flagged_count
                """ + from + where + " ORDER BY report.created_at DESC, report.id DESC LIMIT ? OFFSET ?",
                (result, row) -> new Summary(result.getString("id"), result.getString("conversation_id"),
                        result.getString("student_id"), result.getString("student_name"),
                        result.getString("student_code"), result.getString("reason"),
                        result.getString("status"), result.getLong("flagged_count"),
                        result.getTimestamp("created_at") == null ? null : result.getTimestamp("created_at").toLocalDateTime()), values.toArray());
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Detail detail(UUID id) {
        Header header = jdbc.query("""
                SELECT report.id, report.conversation_id, report.student_id,
                       student.full_name AS student_name, s.student_code,
                       report.reason, report.student_note, report.status,
                       report.resolution_note, report.resolved_at, report.created_at
                FROM ai_response_reports report
                JOIN users student ON student.id = report.student_id
                LEFT JOIN students s ON s.user_id = report.student_id
                WHERE report.id = ?
                """, (result, row) -> new Header(result.getString("id"),
                result.getString("conversation_id"), result.getString("student_id"),
                result.getString("student_name"), result.getString("student_code"),
                result.getString("reason"), result.getString("student_note"),
                result.getString("status"), result.getString("resolution_note"),
                result.getTimestamp("resolved_at") == null ? null
                        : result.getTimestamp("resolved_at").toLocalDateTime(),
                result.getTimestamp("created_at") == null ? null
                        : result.getTimestamp("created_at").toLocalDateTime()), id.toString())
                .stream().findFirst().orElseThrow(NotFound::new);
        List<Message> messages = jdbc.query("""
                SELECT message.id, message.sender, message.content, message.created_at,
                       flagged.id AS flagged_id, flagged.student_note
                FROM ai_messages message
                LEFT JOIN ai_reported_messages flagged ON flagged.message_id = message.id
                    AND flagged.report_id = ?
                WHERE message.conversation_id = ?
                ORDER BY message.created_at, message.id
                """, (result, row) -> new Message(result.getString("id"), result.getString("sender"),
                result.getString("content"),
                result.getTimestamp("created_at") == null ? null : result.getTimestamp("created_at").toLocalDateTime(),
                result.getString("flagged_id") != null, result.getString("student_note")),
                id.toString(), header.conversationId());
        List<Source> sources = jdbc.query("""
                SELECT source.message_id, source.relevance_score, source.quoted_text,
                       source.rank_position, chunk.section_title, doc.title AS document_title
                FROM ai_message_sources source
                JOIN ai_reported_messages flagged ON flagged.message_id = source.message_id
                    AND flagged.report_id = ?
                LEFT JOIN document_chunks chunk ON chunk.id = source.chunk_id
                LEFT JOIN knowledge_documents doc ON doc.id = chunk.knowledge_document_id
                ORDER BY source.message_id, source.rank_position
                """, (result, row) -> new Source(result.getString("message_id"),
                result.getBigDecimal("relevance_score"),
                result.getString("quoted_text"), (Integer) result.getObject("rank_position"),
                result.getString("section_title"), result.getString("document_title")), id.toString());
        return new Detail(header, messages, sources);
    }

    @Transactional
    public void resolve(UUID id, String status, String note, UUID actorId) {
        if (!List.of("RESOLVED", "DISMISSED").contains(status)) {
            throw new IllegalArgumentException("status must be RESOLVED or DISMISSED");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("resolutionNote is required");
        }
        int updated = jdbc.update("""
                UPDATE ai_response_reports
                SET status = ?, resolution_note = ?, resolved_by = ?,
                    resolved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, status, note.strip(), actorId.toString(), id.toString());
        if (updated == 0) {
            boolean exists = jdbc.query("SELECT id FROM ai_response_reports WHERE id = ?",
                    (result, row) -> result.getString(1), id.toString()).size() == 1;
            if (!exists) {
                throw new NotFound();
            }
            throw new Conflict();
        }
    }

    public record Summary(String id, String conversationId, String studentId,
            String studentName, String studentCode, String reason, String status,
            long flaggedCount, LocalDateTime createdAt) {
    }

    public record Header(String id, String conversationId, String studentId,
            String studentName, String studentCode, String reason, String studentNote,
            String status, String resolutionNote, LocalDateTime resolvedAt,
            LocalDateTime createdAt) {
    }

    public record Message(String id, String sender, String content,
            LocalDateTime createdAt, boolean reported, String studentNote) {
    }

    public record Source(String messageId, java.math.BigDecimal relevanceScore,
            String quotedText, Integer rankPosition, String sectionTitle, String documentTitle) {
    }

    public record Detail(Header report, List<Message> messages, List<Source> sources) {
    }

    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {
    }

    public static class NotFound extends RuntimeException {
    }

    public static class Conflict extends RuntimeException {
    }
}
