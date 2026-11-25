package vn.edu.fpt.faaps.ai.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

@Service
public class StudentAiService {

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final StudentRoadmapService roadmaps;
    private final StudentAiChatSupport chatSupport;
    private final GeminiGateway gemini;

    @Autowired
    public StudentAiService(JdbcTemplate jdbc, GeminiGateway gemini) {
        this.jdbc = jdbc;
        this.roadmaps = new StudentRoadmapService(jdbc);
        this.chatSupport = new StudentAiChatSupport(jdbc);
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
                    rs.getString("id"), rs.getString("user_id"), rs.getString("title"),
                    rs.getString("status"), rs.getInt("msg_count"),
                    lastMsg != null ? lastMsg.toLocalDateTime().format(ISO_DT) : null,
                    created != null ? created.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT));
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
        String now = LocalDateTime.now().format(ISO_DT);
        return new AiConversationDto(id, studentId.toString(), title, "ACTIVE", 0, now, now);
    }

    public List<AiChatMessageDto> getMessages(UUID conversationId, UUID studentId) {
        chatSupport.verifyConversationOwner(conversationId, studentId);
        List<AiChatMessageDto> messages = jdbc.query("""
                SELECT id, conversation_id, sender, content, question_category, is_unsupported,
                       is_fallback, confidence_score, ai_configuration_id, was_helpful, feedback, created_at
                FROM ai_messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> chatSupport.mapMessage(rs), conversationId.toString());

        for (int i = 0; i < messages.size(); i++) {
            AiChatMessageDto msg = messages.get(i);
            if ("ASSISTANT".equalsIgnoreCase(msg.sender())) {
                List<AiChatSourceDto> sources = chatSupport.getMessageSources(UUID.fromString(msg.id()));
                messages.set(i, new AiChatMessageDto(
                        msg.id(), msg.conversationId(), msg.sender(), msg.content(),
                        msg.questionCategory(), msg.isUnsupported(), msg.isFallback(),
                        msg.confidenceScore(), msg.aiConfigurationId(), sources,
                        msg.wasHelpful(), msg.feedback(), msg.createdAt()));
            }
        }
        return messages;
    }

    @Transactional
    public SendAiMessageResultDto sendMessage(UUID conversationId, UUID studentId, SendAiMessageDto body) {
        chatSupport.verifyConversationOwner(conversationId, studentId);
        String userContent = body.content().strip();
        if (userContent.isEmpty()) {
            throw new IllegalArgumentException("content cannot be empty");
        }
        String userMsgId = UUID.randomUUID().toString();
        Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());
        String category = chatSupport.classifyQuestion(userContent);

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, created_at)
                VALUES (?, ?, 'USER', ?, ?, FALSE, FALSE, NULL, ?)
                """, userMsgId, conversationId.toString(), userContent, category, nowTs);
        jdbc.update("UPDATE ai_conversations SET last_message_at = ? WHERE id = ?", nowTs, conversationId.toString());

        StudentAiChatSupport.AiConfigModel activeConfig = chatSupport.getActiveChatConfig();
        String assistantMsgId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        GeminiGateway.Reply reply = gemini.generate(
                activeConfig == null ? null : activeConfig.modelName(),
                activeConfig == null ? null : activeConfig.systemPrompt(),
                userContent);
        String answerContent = resolveAnswerContent(reply, activeConfig);
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
        String configId = activeConfig != null ? activeConfig.id() : null;

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, ai_configuration_id, created_at)
                VALUES (?, ?, 'ASSISTANT', ?, ?, FALSE, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, assistantMsgId, conversationId.toString(), answerContent, category, reply.fallback(), configId);
        chatSupport.logAiUsage(studentId, assistantMsgId, configId, reply, latencyMs);

        AiChatMessageDto userMsg = new AiChatMessageDto(
                userMsgId, conversationId.toString(), "USER", userContent, category,
                false, false, null, null, List.of(), null, null, nowTs.toLocalDateTime().format(ISO_DT));
        AiChatMessageDto assistantMsg = new AiChatMessageDto(
                assistantMsgId, conversationId.toString(), "ASSISTANT", answerContent, category,
                false, reply.fallback(), null, configId, List.of(), null, null, LocalDateTime.now().format(ISO_DT));
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
        jdbc.update("UPDATE ai_messages SET was_helpful = ?, feedback = ? WHERE id = ?",
                feedbackDto.wasHelpful(),
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

    private String resolveAnswerContent(GeminiGateway.Reply reply, StudentAiChatSupport.AiConfigModel activeConfig) {
        if (!reply.fallback()) {
            return reply.text();
        }
        if (activeConfig != null && activeConfig.fallbackMessage() != null && !activeConfig.fallbackMessage().isBlank()) {
            return activeConfig.fallbackMessage();
        }
        return "The academic assistant is unavailable. Please contact your advisor.";
    }
}
