package vn.edu.fpt.faaps.student.service;

import vn.edu.fpt.faaps.student.dto.StudentAiDtos;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.AiChatMessageDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.AiChatSourceDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.AiConversationDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.AiMessageFeedbackDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.CreateAiConversationDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.student.dto.StudentAiDtos.StudyRoadmapDto;

@Service
public class StudentAiService {

    private final JdbcTemplate jdbc;
    private final StudentRoadmapService roadmaps;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public StudentAiService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.roadmaps = new StudentRoadmapService(jdbc);
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
        String answerContent = generateAnswer(userContent, category, activeConfig);

        String configId = activeConfig != null ? activeConfig.id : null;
        double confidence = 0.92;

        jdbc.update("""
                INSERT INTO ai_messages (id, conversation_id, sender, content, question_category, is_unsupported,
                                         is_fallback, confidence_score, ai_configuration_id, created_at)
                VALUES (?, ?, 'ASSISTANT', ?, ?, FALSE, FALSE, ?, ?, CURRENT_TIMESTAMP)
                """, assistantMsgId, conversationId.toString(), answerContent, category,
                BigDecimal.valueOf(confidence), configId);

        List<AiChatSourceDto> sources = linkKnowledgeSources(assistantMsgId, userContent);

        logAiUsage(studentId, assistantMsgId, configId, userContent, answerContent);

        AiChatMessageDto userMsg = new AiChatMessageDto(
                userMsgId, conversationId.toString(), "USER", userContent, category,
                false, false, null, null, List.of(), null, null, nowTs.toLocalDateTime().format(ISO_DT)
        );

        AiChatMessageDto assistantMsg = new AiChatMessageDto(
                assistantMsgId, conversationId.toString(), "ASSISTANT", answerContent, category,
                false, false, confidence, configId, sources, null, null,
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

    private List<AiChatSourceDto> linkKnowledgeSources(String messageId, String query) {
        List<Map<String, Object>> chunks = jdbc.queryForList("""
                SELECT chunk.id, chunk.section_title, doc.title AS doc_title
                FROM document_chunks chunk
                JOIN knowledge_documents doc ON doc.id = chunk.knowledge_document_id
                LIMIT 2
                """);

        List<AiChatSourceDto> sources = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> chunk : chunks) {
            String chunkId = (String) chunk.get("id");
            String docTitle = (String) chunk.get("doc_title");
            String sectionTitle = (String) chunk.get("section_title");
            String quote = "Excerpt regarding university academic policies and prerequisites for " + query;
            double score = 0.88 - (rank * 0.05);

            jdbc.update("""
                    INSERT INTO ai_message_sources (id, message_id, chunk_id, relevance_score, quoted_text, rank_position)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), messageId, chunkId, BigDecimal.valueOf(score), quote, rank);

            sources.add(new AiChatSourceDto(chunkId, docTitle, sectionTitle, quote, score, rank));
            rank++;
        }
        return sources;
    }

    private void logAiUsage(UUID studentId, String messageId, String configId, String query, String answer) {
        int promptTokens = query.length() / 4 + 10;
        int completionTokens = answer.length() / 4 + 10;
        int totalTokens = promptTokens + completionTokens;

        jdbc.update("""
                INSERT INTO ai_usage_logs (id, user_id, message_id, ai_configuration_id, feature,
                                           prompt_tokens, completion_tokens, total_tokens, latency_ms, status)
                VALUES (?, ?, ?, ?, 'CHAT', ?, ?, ?, 320, 'SUCCESS')
                """, UUID.randomUUID().toString(), studentId.toString(), messageId, configId,
                promptTokens, completionTokens, totalTokens);
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

    private String generateAnswer(String query, String category, AiConfigModel config) {
        return switch (category) {
            case "POLICY" -> "According to university academic regulations, students must maintain a minimum cumulative GPA of 2.0. In order to pass a course, each component grade and the final examination score must satisfy the passing threshold (typically >= 4.0/10).";
            case "COURSE_INFO" -> "Prerequisites must be passed prior to enrolling in subsequent specialized courses. You can check the course prerequisite chain on your curriculum page or study roadmap.";
            case "STUDY_PLAN" -> "You can generate a personalized study roadmap directly from the AI Roadmap tab. It calculates the optimal semester course sequence adhering strictly to prerequisite chains and your preferred credit limits.";
            case "GRADUATION" -> "To qualify for graduation, you must complete all 140 required credits specified in your curriculum, fulfill the On-the-Job Training (OJT) semester, and successfully defend your Capstone Project.";
            default -> "I am your FAAPS Academic AI Advisor. I can help answer questions regarding university academic regulations, prerequisites, retakes, and study roadmap planning.";
        };
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
