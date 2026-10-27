package vn.edu.fpt.faaps.service;

import vn.edu.fpt.faaps.dto.StudentAiDtos;
import vn.edu.fpt.faaps.service.StudentAiService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.dto.StudentAiDtos.AiConversationDto;
import vn.edu.fpt.faaps.dto.StudentAiDtos.CreateAiConversationDto;
import vn.edu.fpt.faaps.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.dto.StudentAiDtos.StudyRoadmapDto;

class StudentAiServiceTest {

    private JdbcTemplate jdbc;
    private StudentAiService service;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:student-ai-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50), major_id VARCHAR(36), curriculum_version_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))");
        jdbc.execute("CREATE TABLE courses (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255), credits INT, category_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE course_categories (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE curriculum_courses (id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36), course_id VARCHAR(36), semester_number INT, is_mandatory BOOLEAN)");
        jdbc.execute("CREATE TABLE course_prerequisites (id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36), prerequisite_course_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE document_chunks (id VARCHAR(36) PRIMARY KEY, knowledge_document_id VARCHAR(36), section_title VARCHAR(255))");
        jdbc.execute("CREATE TABLE knowledge_documents (id VARCHAR(36) PRIMARY KEY, title VARCHAR(255))");

        jdbc.execute("""
                CREATE TABLE ai_conversations (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id VARCHAR(36),
                    title VARCHAR(255),
                    status VARCHAR(20),
                    last_message_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_messages (
                    id VARCHAR(36) PRIMARY KEY,
                    conversation_id VARCHAR(36),
                    sender VARCHAR(20),
                    content TEXT,
                    question_category VARCHAR(30),
                    is_unsupported BOOLEAN,
                    is_fallback BOOLEAN,
                    confidence_score DECIMAL(5,4),
                    ai_configuration_id VARCHAR(36),
                    was_helpful BOOLEAN,
                    feedback TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_message_sources (
                    id VARCHAR(36) PRIMARY KEY,
                    message_id VARCHAR(36),
                    chunk_id VARCHAR(36),
                    relevance_score DECIMAL(5,4),
                    quoted_text TEXT,
                    rank_position INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_configurations (
                    id VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(100),
                    provider VARCHAR(20),
                    model_name VARCHAR(100),
                    purpose VARCHAR(20),
                    system_prompt TEXT,
                    fallback_message TEXT,
                    is_active BOOLEAN,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_usage_logs (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id VARCHAR(36),
                    message_id VARCHAR(36),
                    ai_configuration_id VARCHAR(36),
                    feature VARCHAR(20),
                    prompt_tokens INT,
                    completion_tokens INT,
                    total_tokens INT,
                    latency_ms INT,
                    status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE study_plans (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36),
                    semester_id VARCHAR(36),
                    version INT,
                    is_current BOOLEAN,
                    plan_type VARCHAR(20),
                    total_credits_planned INT,
                    status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE study_plan_courses (
                    id VARCHAR(36) PRIMARY KEY,
                    study_plan_id VARCHAR(36),
                    course_id VARCHAR(36),
                    planned_credits INT,
                    priority VARCHAR(20),
                    notes TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_recommendations (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36),
                    course_id VARCHAR(36),
                    semester_id VARCHAR(36),
                    recommendation_source VARCHAR(20),
                    confidence_score DECIMAL(5,4),
                    reasoning TEXT,
                    priority VARCHAR(20),
                    status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        studentId = UUID.randomUUID();
        String curVerId = UUID.randomUUID().toString();
        String semId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO semesters VALUES (?, 'SP2026')", semId);
        jdbc.update("INSERT INTO users VALUES (?, 'Bob Student')", studentId.toString());
        jdbc.update("INSERT INTO students VALUES (?, 'SE999', 'SE', ?)", studentId.toString(), curVerId);

        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO courses VALUES (?, 'PRF192', 'Programming Fundamentals', 3, NULL)", c1);
        jdbc.update("INSERT INTO courses VALUES (?, 'PRO192', 'Object-Oriented Programming', 3, NULL)", c2);
        jdbc.update("INSERT INTO course_prerequisites VALUES (?, ?, ?)", UUID.randomUUID().toString(), c2, c1);
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, ?, 1, TRUE)", UUID.randomUUID().toString(), curVerId, c1);
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, ?, 2, TRUE)", UUID.randomUUID().toString(), curVerId, c2);

        service = new StudentAiService(jdbc);
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
        assertTrue(result.assistantMessage().content().contains("GPA"));
        assertEquals(2, service.getMessages(UUID.fromString(conv.id()), studentId).size());
    }

    @Test
    void testGenerateRoadmapFlow() {
        StudyRoadmapDto roadmap = service.generateRoadmap(studentId, new GenerateRoadmapDto("Graduate on time", 20));
        assertNotNull(roadmap.id());
        assertEquals(studentId.toString(), roadmap.studentId());
        assertTrue(roadmap.semesters().size() >= 1);
        assertEquals(6, roadmap.totalCredits());
    }
}

