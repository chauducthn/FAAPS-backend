package vn.edu.fpt.faaps.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.CreateAiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

class StudentAiServiceTest {

    private JdbcTemplate jdbc;
    private StudentAiService service;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        StudentAiTestSupport.Fixture fixture = StudentAiTestSupport.createFixture();
        jdbc = fixture.jdbc();
        service = fixture.service();
        studentId = fixture.studentId();
    }

    @Test
    void testConversationChatFlow() {
        AiConversationDto conv = service.createConversation(studentId, new CreateAiConversationDto("My academic advice"));
        assertNotNull(conv.id());
        assertEquals("My academic advice", conv.title());

        SendAiMessageResultDto result = service.sendMessage(
                UUID.fromString(conv.id()), studentId, new SendAiMessageDto("What is the minimum GPA policy?")
        );

        assertNotNull(result.userMessage());
        assertNotNull(result.assistantMessage());
        assertEquals("POLICY", result.userMessage().questionCategory());
        assertTrue(result.assistantMessage().isFallback());
        assertTrue(result.assistantMessage().content().contains("unavailable"));
        assertTrue(result.assistantMessage().sources().isEmpty());
        assertEquals("FALLBACK", jdbc.queryForObject(
                "SELECT status FROM ai_usage_logs WHERE message_id=?", String.class,
                result.assistantMessage().id()));
        assertEquals(2, service.getMessages(UUID.fromString(conv.id()), studentId).size());
    }

    @Test
    void testGenerateRoadmapFlow() {
        StudyRoadmapDto roadmap = service.generateRoadmap(studentId, new GenerateRoadmapDto("Graduate on time", 20));
        assertNotNull(roadmap.id());
        assertEquals(studentId.toString(), roadmap.studentId());
        assertTrue(roadmap.semesters().size() >= 1);
        assertEquals(6, roadmap.totalCredits());
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM study_plans WHERE id=?", String.class, roadmap.id()));
        assertEquals("CURRICULUM", jdbc.queryForObject(
                "SELECT recommendation_source FROM course_recommendations WHERE student_id=? LIMIT 1",
                String.class, studentId.toString()));
    }
}
