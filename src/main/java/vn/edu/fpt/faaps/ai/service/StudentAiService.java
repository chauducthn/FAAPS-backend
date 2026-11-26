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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.*;

@Service
public class StudentAiService {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final JdbcTemplate jdbc;
    private final StudentRoadmapService roadmaps;
    private final StudentAiChatSupport chatSupport;
    private final AiAcademicContextBuilder contextBuilder;
    private final StudentAiGenerationHelper generator;
    private final StudentAiAttachmentHelper attachments;

    @Autowired
    public StudentAiService(JdbcTemplate jdbc, GeminiGateway gemini, AiSecretStore secretStore,
            @Autowired(required = false) TranscriptImportService transcriptImport,
            @Autowired(required = false) GeminiVisionClient visionClient) {
        this.jdbc = jdbc;
        this.roadmaps = new StudentRoadmapService(jdbc);
        this.chatSupport = new StudentAiChatSupport(jdbc);
        this.contextBuilder = new AiAcademicContextBuilder(jdbc);
        this.generator = new StudentAiGenerationHelper(gemini, secretStore);
        this.attachments = new StudentAiAttachmentHelper(transcriptImport, visionClient);
    }

    public StudentAiService(JdbcTemplate jdbc, GeminiGateway gemini, AiSecretStore secretStore) {
        this(jdbc, gemini, secretStore, null, null);
    }

    public StudentAiService(JdbcTemplate jdbc, GeminiGateway gemini) {
        this(jdbc, gemini, new AiSecretStore(jdbc), null, null);
    }

    StudentAiService(JdbcTemplate jdbc) {
        this(jdbc, new GeminiGateway("", new com.fasterxml.jackson.databind.ObjectMapper()),
                new AiSecretStore(jdbc), null, null);
    }

    public List<AiConversationDto> listConversations(UUID studentId) {
        return jdbc.query("""
                SELECT c.id, c.user_id, c.title, c.status, c.last_message_at, c.created_at,
                       (SELECT COUNT(*) FROM ai_messages m WHERE m.conversation_id = c.id) AS msg_count
                FROM ai_conversations c WHERE c.user_id = ?
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
                ? dto.title().strip() : "New Advising Chat";
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
                FROM ai_messages WHERE conversation_id = ? ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> chatSupport.mapMessage(rs), conversationId.toString());
        for (int i = 0; i < messages.size(); i++) {
            AiChatMessageDto msg = messages.get(i);
            if ("ASSISTANT".equalsIgnoreCase(msg.sender())) {
                messages.set(i, new AiChatMessageDto(
                        msg.id(), msg.conversationId(), msg.sender(), msg.content(), msg.questionCategory(),
                        msg.isUnsupported(), msg.isFallback(), msg.confidenceScore(), msg.aiConfigurationId(),
                        chatSupport.getMessageSources(UUID.fromString(msg.id())),
                        msg.wasHelpful(), msg.feedback(), msg.createdAt()));
            }
        }
        return messages;
    }

    @Transactional
    public SendAiMessageResultDto sendMessage(UUID conversationId, UUID studentId, SendAiMessageDto body) {
        return processMessage(conversationId, studentId, body.content(), null);
    }

    @Transactional
    public SendAiMessageResultDto sendMessageWithAttachment(
            UUID conversationId, UUID studentId, String content, MultipartFile file) {
        return processMessage(conversationId, studentId, content, file);
    }

    private SendAiMessageResultDto processMessage(
            UUID conversationId, UUID studentId, String rawContent, MultipartFile file) {
        chatSupport.verifyConversationOwner(conversationId, studentId);
        String text = rawContent == null ? "" : rawContent.strip();
        if (text.isEmpty() && (file == null || file.isEmpty())) {
            throw new IllegalArgumentException("content or file is required");
        }
        var attachment = (file != null && !file.isEmpty()) ? attachments.process(studentId, text, file) : null;
        String storedUserContent = attachment == null ? text
                : (text.isEmpty() ? "[Attached: " + attachment.fileName() + "]"
                        : "[Attached: " + attachment.fileName() + "]\n" + text);
        var activeConfigs = chatSupport.getActiveChatConfigs();
        int memoryLimit = activeConfigs.isEmpty() || activeConfigs.get(0).retrievalTopK() == null
                ? 12 : activeConfigs.get(0).retrievalTopK();
        List<GeminiGateway.Turn> history = contextBuilder.loadConversationMemory(conversationId, memoryLimit);
        String academicContext = contextBuilder.buildStudentContext(studentId, storedUserContent)
                + (attachment != null ? attachment.extraContext() : "");

        String userMsgId = UUID.randomUUID().toString();
        Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());
        String category = chatSupport.classifyQuestion(storedUserContent);
        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, created_at)
                VALUES (?, ?, 'USER', ?, ?, FALSE, FALSE, NULL, ?)
                """, userMsgId, conversationId.toString(), storedUserContent, category, nowTs);
        jdbc.update("UPDATE ai_conversations SET last_message_at = ? WHERE id = ?", nowTs, conversationId.toString());
        chatSupport.maybeAutoTitle(conversationId, text.isEmpty() && attachment != null ? attachment.fileName() : text);

        String assistantMsgId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        var outcome = generator.generateWithFallback(activeConfigs, academicContext, history, storedUserContent);
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
        boolean usedActionFallback = outcome.reply().fallback()
                && attachment != null && attachment.directFallbackReply() != null;
        String answerContent = usedActionFallback
                ? attachment.directFallbackReply() : generator.resolveAnswerContent(outcome.reply(), outcome.config());
        boolean isFallback = outcome.reply().fallback() && !usedActionFallback;
        String configId = outcome.config() != null ? outcome.config().id() : null;

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, ai_configuration_id, created_at)
                VALUES (?, ?, 'ASSISTANT', ?, ?, FALSE, ?, NULL, ?, CURRENT_TIMESTAMP)
                """, assistantMsgId, conversationId.toString(), answerContent, category, isFallback, configId);
        chatSupport.logAiUsage(studentId, assistantMsgId, configId, outcome.reply(), latencyMs);

        AiChatMessageDto userMsg = new AiChatMessageDto(userMsgId, conversationId.toString(), "USER",
                storedUserContent, category, false, false, null, null, List.of(), null, null,
                nowTs.toLocalDateTime().format(ISO_DT));
        AiChatMessageDto assistantMsg = new AiChatMessageDto(assistantMsgId, conversationId.toString(), "ASSISTANT",
                answerContent, category, false, isFallback, null, configId, List.of(), null, null,
                LocalDateTime.now().format(ISO_DT));
        return new SendAiMessageResultDto(userMsg, assistantMsg);
    }

    @Transactional
    public void sendFeedback(UUID messageId, UUID studentId, AiMessageFeedbackDto feedbackDto) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_messages m JOIN ai_conversations c ON c.id = m.conversation_id
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
}
