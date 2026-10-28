package vn.edu.fpt.faaps.ai.service;

import vn.edu.fpt.faaps.ai.dto.StudentAiDtos;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiChatMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiChatSourceDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiMessageFeedbackDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.CreateAiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

@Service
public class StudentAiService {

    private final JdbcTemplate jdbc;
    private final StudentRoadmapService roadmaps;
    private final GeminiGateway gemini;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    public StudentAiService(JdbcTemplate jdbc, GeminiGateway gemini) {
        this.jdbc = jdbc;
        this.roadmaps = new StudentRoadmapService(jdbc);
        this.gemini = gemini;
    }

    StudentAiService(JdbcTemplate jdbc) {
        this(jdbc, new GeminiGateway("", new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public List<AiConversationDto> listConversations(UUID studentId) {
        return jdbc.query("""
                SELECT c.id, c.user_id, c.title, c.status, c.last_message_at, c.created_at,
                       (SELECT COUNT(*) FROM ai_messages m WHERE m.conversation_id = c.id) AS msg_count
                FROM ai_conversations c
                WHERE c.user_id = ?
                ORDER BY COALESCE(c.last_message_at, c.created_at) DESC
                """, (rs, rowNum) -> {
            Timestamp lastMsg = rs.getTimestamp("last_message_at");
            Timestamp created = rs.getTimestamp("created_at");
            return new AiConversationDto(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getInt("msg_count"),
                    lastMsg != null ? lastMsg.toLocalDateTime().format(ISO_DT) : null,
                    created != null ? created.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT)
            );
        }, studentId.toString());
    }

    @Transactional
    public AiConversationDto createConversation(UUID studentId, CreateAiConversationDto dto) {
        String id = UUID.randomUUID().toString();
        String title = dto != null && dto.title() != null && !dto.title().isBlank()
                ? dto.title().strip()
                : "New Advising Chat";

        jdbc.update("""
                INSERT INTO ai_conversations (id, user_id, title, status, last_message_at, created_at)
                VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, studentId.toString(), title);

        return new AiConversationDto(
                id,
                studentId.toString(),
                title,
                "ACTIVE",
                0,
                LocalDateTime.now().format(ISO_DT),
                LocalDateTime.now().format(ISO_DT)
        );
    }

    public List<AiChatMessageDto> getMessages(UUID conversationId, UUID studentId) {
        verifyConversationOwner(conversationId, studentId);

        List<AiChatMessageDto> messages = jdbc.query("""
                SELECT id, conversation_id, sender, content, question_category, is_unsupported,
                       is_fallback, confidence_score, ai_configuration_id, was_helpful, feedback, created_at
                FROM ai_messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> mapMessage(rs), conversationId.toString());

        for (int i = 0; i < messages.size(); i++) {
            AiChatMessageDto msg = messages.get(i);
            if ("ASSISTANT".equalsIgnoreCase(msg.sender())) {
                List<AiChatSourceDto> sources = getMessageSources(UUID.fromString(msg.id()));
                messages.set(i, new AiChatMessageDto(
                        msg.id(), msg.conversationId(), msg.sender(), msg.content(),
                        msg.questionCategory(), msg.isUnsupported(), msg.isFallback(),
                        msg.confidenceScore(), msg.aiConfigurationId(), sources,
                        msg.wasHelpful(), msg.feedback(), msg.createdAt()
                ));
            }
        }

        return messages;
    }

    @Transactional
    public SendAiMessageResultDto sendMessage(UUID conversationId, UUID studentId, SendAiMessageDto body) {
        verifyConversationOwner(conversationId, studentId);
        String userContent = body.content().strip();
        if (userContent.isEmpty()) {
            throw new IllegalArgumentException("content cannot be empty");
        }

        String userMsgId = UUID.randomUUID().toString();
        Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());

        String category = classifyQuestion(userContent);

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, created_at)
                VALUES (?, ?, 'USER', ?, ?, FALSE, FALSE, NULL, ?)
                """, userMsgId, conversationId.toString(), userContent, category, nowTs);

        jdbc.update("UPDATE ai_conversations SET last_message_at = ? WHERE id = ?", nowTs, conversationId.toString());

        AiConfigModel activeConfig = getActiveChatConfig();
        String assistantMsgId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        GeminiGateway.Reply reply = gemini.generate(activeConfig == null ? null : activeConfig.modelName(),
                activeConfig == null ? null : activeConfig.systemPrompt(), userContent);
        String answerContent = reply.fallback()
                ? activeConfig != null && activeConfig.fallbackMessage() != null && !activeConfig.fallbackMessage().isBlank()
                    ? activeConfig.fallbackMessage() : "The academic assistant is unavailable. Please contact your advisor."
                : reply.text();
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);

        String configId = activeConfig != null ? activeConfig.id : null;

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, ai_configuration_id, created_at)
                VALUES (?, ?, 'ASSISTANT', ?, ?, FALSE, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, assistantMsgId, conversationId.toString(), answerContent, category,
                reply.fallback(), configId);

        List<AiChatSourceDto> sources = List.of();

        logAiUsage(studentId, assistantMsgId, configId, reply, latencyMs);

        AiChatMessageDto userMsg = new AiChatMessageDto(
                userMsgId, conversationId.toString(), "USER", userContent, category,
                false, false, null, null, List.of(), null, null, nowTs.toLocalDateTime().format(ISO_DT)
        );

        AiChatMessageDto assistantMsg = new AiChatMessageDto(
                assistantMsgId, conversationId.toString(), "ASSISTANT", answerContent, category,
                false, reply.fallback(), null, configId, sources, null, null,
                LocalDateTime.now().format(ISO_DT)
        );

        return new SendAiMessageResultDto(userMsg, assistantMsg);
    }

    @Transactional
    public void sendFeedback(UUID messageId, UUID studentId, AiMessageFeedbackDto feedbackDto) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_messages m
                JOIN ai_conversations c ON c.id = m.conversation_id
                WHERE m.id = ? AND c.user_id = ?
                """, Integer.class, messageId.toString(), studentId.toString());

        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found or unauthorized");
        }

        jdbc.update("""
                UPDATE ai_messages
                SET was_helpful = ?, feedback = ?
                WHERE id = ?
                """, feedbackDto.wasHelpful(),
                feedbackDto.feedback() != null ? feedbackDto.feedback().strip() : null,
                messageId.toString());
    }

    public StudyRoadmapDto getRoadmap(UUID studentId) {
        return roadmaps.get(studentId);
    }

    @Transactional
    public StudyRoadmapDto generateRoadmap(UUID studentId, GenerateRoadmapDto dto) {
        return roadmaps.generate(studentId, dto);
    }

    private void verifyConversationOwner(UUID conversationId, UUID studentId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversations WHERE id = ? AND user_id = ?",
                Integer.class, conversationId.toString(), studentId.toString());
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found or unauthorized");
        }
    }

    private AiChatMessageDto mapMessage(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Double conf = rs.getObject("confidence_score") != null ? rs.getBigDecimal("confidence_score").doubleValue() : null;
        return new AiChatMessageDto(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("sender"),
                rs.getString("content"),
                rs.getString("question_category"),
                rs.getBoolean("is_unsupported"),
                rs.getBoolean("is_fallback"),
                conf,
                rs.getString("ai_configuration_id"),
                new ArrayList<>(),
                rs.getObject("was_helpful") != null ? rs.getBoolean("was_helpful") : null,
                rs.getString("feedback"),
                created != null ? created.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT)
        );
    }

    private List<AiChatSourceDto> getMessageSources(UUID messageId) {
        return jdbc.query("""
                SELECT s.chunk_id, doc.title AS doc_title, chunk.section_title, s.quoted_text,
                       s.relevance_score, s.rank_position
                FROM ai_message_sources s
                LEFT JOIN document_chunks chunk ON chunk.id = s.chunk_id
                LEFT JOIN knowledge_documents doc ON doc.id = chunk.knowledge_document_id
                WHERE s.message_id = ?
                ORDER BY s.rank_position ASC
                """, (rs, rowNum) -> {
            Double score = rs.getObject("relevance_score") != null ? rs.getBigDecimal("relevance_score").doubleValue() : null;
            return new AiChatSourceDto(
                    rs.getString("chunk_id"),
                    rs.getString("doc_title") != null ? rs.getString("doc_title") : "University Academic Regulations",
                    rs.getString("section_title"),
                    rs.getString("quoted_text"),
                    score,
                    (Integer) rs.getObject("rank_position")
            );
        }, messageId.toString());
    }

    private void logAiUsage(UUID studentId, String messageId, String configId,
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

    private String classifyQuestion(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("gpa") || lower.contains("điểm") || lower.contains("quy chế") || lower.contains("policy")) {
            return "POLICY";
        }
        if (lower.contains("môn") || lower.contains("tiên quyết") || lower.contains("prerequisite") || lower.contains("course")) {
            return "COURSE_INFO";
        }
        if (lower.contains("kỳ") || lower.contains("kế hoạch") || lower.contains("plan") || lower.contains("lộ trình") || lower.contains("roadmap")) {
            return "STUDY_PLAN";
        }
        if (lower.contains("tốt nghiệp") || lower.contains("graduate") || lower.contains("graduation")) {
            return "GRADUATION";
        }
        return "OTHER";
    }

    private AiConfigModel getActiveChatConfig() {
        return jdbc.query("""
                SELECT id, name, model_name, system_prompt, fallback_message
                FROM ai_configurations
                WHERE purpose = 'CHAT' AND is_active = TRUE
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rowNum) -> new AiConfigModel(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("model_name"),
                rs.getString("system_prompt"),
                rs.getString("fallback_message")
        )).stream().findFirst().orElse(null);
    }

    private record AiConfigModel(String id, String name, String modelName, String systemPrompt, String fallbackMessage) {}
}


