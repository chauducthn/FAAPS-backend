package vn.edu.fpt.faaps.ai.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiChatMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiChatSourceDto;

final class StudentAiChatSupport {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;

    StudentAiChatSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final String DEFAULT_ADVISOR_PROMPT =
            "You are FAAPS, a dedicated AI Academic Advisor for FPT University (FPTU) students. "
            + "Respond warmly and clearly in the student's language (Vietnamese or English). "
            + "1) Read LIVE FAAPS ACADEMIC CONTEXT carefully to personalize guidance: analyze the student's GPA, "
            + "strong subjects, weak/failed subjects, open warnings, and curriculum progress. "
            + "2) Provide concrete study strategies: how to study specific courses, prerequisite order, credit load "
            + "balance, and retake/improvement plans. "
            + "3) If MISSING STUDENT DATA is present (e.g., no transcript or major/curriculum yet), proactively "
            + "inform the student what is missing and invite them to upload their transcript (/student/transcript), "
            + "set Curriculum & Goals (/student/curriculum-goals), or share their subjects/grades right in this chat.";

    void maybeAutoTitle(UUID conversationId, String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            return;
        }
        String compact = firstUserMessage.replaceAll("\\s+", " ").strip();
        String title = compact.length() > 54 ? compact.substring(0, 51).strip() + "..." : compact;
        jdbc.update("""
                UPDATE ai_conversations
                SET title = ?
                WHERE id = ? AND (title IS NULL OR title = '' OR title = 'New Advising Chat')
                """, title, conversationId.toString());
    }

    void verifyConversationOwner(UUID conversationId, UUID studentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_conversations WHERE id = ? AND user_id = ?",
                Integer.class, conversationId.toString(), studentId.toString());
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found or unauthorized");
        }
    }

    AiChatMessageDto mapMessage(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Double conf = rs.getObject("confidence_score") != null
                ? rs.getBigDecimal("confidence_score").doubleValue()
                : null;
        return new AiChatMessageDto(
                rs.getString("id"), rs.getString("conversation_id"), rs.getString("sender"),
                rs.getString("content"), rs.getString("question_category"), rs.getBoolean("is_unsupported"),
                rs.getBoolean("is_fallback"), conf, rs.getString("ai_configuration_id"),
                new ArrayList<>(),
                rs.getObject("was_helpful") != null ? rs.getBoolean("was_helpful") : null,
                rs.getString("feedback"),
                created != null ? created.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT));
    }

    List<AiChatSourceDto> getMessageSources(UUID messageId) {
        return jdbc.query("""
                SELECT s.chunk_id, doc.title AS doc_title, chunk.section_title, s.quoted_text,
                       s.relevance_score, s.rank_position
                FROM ai_message_sources s
                LEFT JOIN document_chunks chunk ON chunk.id = s.chunk_id
                LEFT JOIN knowledge_documents doc ON doc.id = chunk.knowledge_document_id
                WHERE s.message_id = ?
                ORDER BY s.rank_position ASC
                """, (rs, rowNum) -> {
            Double score = rs.getObject("relevance_score") != null
                    ? rs.getBigDecimal("relevance_score").doubleValue()
                    : null;
            String docTitle = rs.getString("doc_title");
            return new AiChatSourceDto(
                    rs.getString("chunk_id"),
                    docTitle != null ? docTitle : "University Academic Regulations",
                    rs.getString("section_title"),
                    rs.getString("quoted_text"),
                    score,
                    (Integer) rs.getObject("rank_position"));
        }, messageId.toString());
    }

    void logAiUsage(UUID studentId, String messageId, String configId,
            GeminiGateway.Reply reply, int latencyMs) {
        int totalTokens = reply.promptTokens() + reply.completionTokens();
        jdbc.update("""
                INSERT INTO ai_usage_logs (id, user_id, message_id, ai_configuration_id, feature,
                                           prompt_tokens, completion_tokens, total_tokens, latency_ms, status)
                VALUES (?, ?, ?, ?, 'CHAT', ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), studentId.toString(), messageId, configId,
                reply.promptTokens(), reply.completionTokens(), totalTokens, latencyMs,
                reply.fallback() ? "FALLBACK" : "SUCCESS");
    }

    String classifyQuestion(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("gpa") || lower.contains("điểm")
                || lower.contains("quy chế") || lower.contains("policy")) {
            return "POLICY";
        }
        if (lower.contains("môn") || lower.contains("tiên quyết")
                || lower.contains("prerequisite") || lower.contains("course")) {
            return "COURSE_INFO";
        }
        if (lower.contains("kỳ") || lower.contains("kế hoạch")
                || lower.contains("plan") || lower.contains("lộ trình") || lower.contains("roadmap")) {
            return "STUDY_PLAN";
        }
        if (lower.contains("tốt nghiệp") || lower.contains("graduate") || lower.contains("graduation")) {
            return "GRADUATION";
        }
        return "OTHER";
    }

    List<AiConfigModel> getActiveChatConfigs() {
        try {
            return jdbc.query("""
                    SELECT id, name, provider, model_name, temperature, max_tokens, retrieval_top_k,
                           timeout_seconds, system_prompt, fallback_message
                    FROM ai_configurations
                    WHERE purpose = 'CHAT' AND is_active = TRUE
                    ORDER BY updated_at DESC, created_at DESC
                    LIMIT 5
                    """, (rs, rowNum) -> new AiConfigModel(
                    rs.getString("id"), rs.getString("name"), rs.getString("provider"),
                    rs.getString("model_name"),
                    rs.getObject("temperature") != null ? rs.getBigDecimal("temperature").doubleValue() : 0.5,
                    (Integer) rs.getObject("max_tokens"), (Integer) rs.getObject("retrieval_top_k"),
                    (Integer) rs.getObject("timeout_seconds"),
                    rs.getString("system_prompt"), rs.getString("fallback_message")));
        } catch (Exception ex) {
            return jdbc.query("""
                    SELECT id, name, model_name, system_prompt, fallback_message
                    FROM ai_configurations
                    WHERE purpose = 'CHAT' AND is_active = TRUE
                    ORDER BY created_at DESC LIMIT 5
                    """, (rs, rowNum) -> new AiConfigModel(
                    rs.getString("id"), rs.getString("name"), "GEMINI", rs.getString("model_name"),
                    0.5, 4096, 12, 30, rs.getString("system_prompt"), rs.getString("fallback_message")));
        }
    }

    record AiConfigModel(String id, String name, String provider, String modelName, Double temperature,
            Integer maxTokens, Integer retrievalTopK, Integer timeoutSeconds,
            String systemPrompt, String fallbackMessage) {
    }
}
