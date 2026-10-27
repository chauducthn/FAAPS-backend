package vn.edu.fpt.faaps.ai.service;

import vn.edu.fpt.faaps.ai.service.AdminAiReportService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminAiReportServiceTest {
    private JdbcTemplate jdbc;
    private AdminAiReportService reports;
    private UUID adminId;
    private UUID reportId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-reports-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36), student_code VARCHAR(50))");
        jdbc.execute("CREATE TABLE ai_response_reports (id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36), student_id VARCHAR(36), reason VARCHAR(100), student_note VARCHAR(500), status VARCHAR(20), resolution_note VARCHAR(500), resolved_by VARCHAR(36), resolved_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_reported_messages (id VARCHAR(36), report_id VARCHAR(36), message_id VARCHAR(36), student_note VARCHAR(500))");
        jdbc.execute("CREATE TABLE ai_messages (id VARCHAR(36), conversation_id VARCHAR(36), sender VARCHAR(20), content VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_message_sources (message_id VARCHAR(36), chunk_id VARCHAR(36), relevance_score DECIMAL(5,4), quoted_text VARCHAR(500), rank_position INT)");
        jdbc.execute("CREATE TABLE document_chunks (id VARCHAR(36), knowledge_document_id VARCHAR(36), section_title VARCHAR(255))");
        jdbc.execute("CREATE TABLE knowledge_documents (id VARCHAR(36), title VARCHAR(255))");
        adminId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, 'Admin')", adminId.toString());
        jdbc.update("INSERT INTO users VALUES (?, 'Student')", studentId.toString());
        jdbc.update("INSERT INTO students VALUES (?, 'SE123')", studentId.toString());
        jdbc.update("INSERT INTO ai_response_reports (id, conversation_id, student_id, reason, status) VALUES (?, ?, ?, 'INCORRECT_RULE', 'PENDING')",
                reportId.toString(), conversationId.toString(), studentId.toString());
        jdbc.update("INSERT INTO ai_messages (id, conversation_id, sender, content) VALUES (?, ?, 'ASSISTANT', 'Wrong answer')",
                messageId.toString(), conversationId.toString());
        jdbc.update("INSERT INTO ai_reported_messages (id, report_id, message_id) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), reportId.toString(), messageId.toString());
        reports = new AdminAiReportService(jdbc);
    }

    @Test
    void listsPendingReportsWithFlaggedConversationAndResolvesOnce() {
        assertEquals(1, reports.list("PENDING", "SE123", 0, 20).totalElements());
        assertEquals(1, reports.detail(reportId).messages().size());
        assertEquals(true, reports.detail(reportId).messages().get(0).reported());
        reports.resolve(reportId, "RESOLVED", "Corrected rule", adminId);
        assertEquals("RESOLVED", reports.detail(reportId).report().status());
        assertThrows(AdminAiReportService.Conflict.class, () ->
                reports.resolve(reportId, "DISMISSED", "No issue", adminId));
    }
}

