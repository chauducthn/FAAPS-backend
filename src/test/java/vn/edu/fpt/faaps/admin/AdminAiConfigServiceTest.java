package vn.edu.fpt.faaps.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.AiConfigDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.admin.AdminAiConfigDtos.UpdateAiConfigDto;

class AdminAiConfigServiceTest {

    private JdbcTemplate jdbc;
    private AdminAiConfigService service;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ai-config-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
                CREATE TABLE ai_configurations (
                    id VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(100) UNIQUE,
                    provider VARCHAR(20),
                    model_name VARCHAR(100),
                    purpose VARCHAR(20),
                    temperature DECIMAL(3,2),
                    max_tokens INT,
                    retrieval_top_k INT,
                    similarity_threshold DECIMAL(5,4),
                    timeout_seconds INT,
                    daily_request_limit INT,
                    system_prompt TEXT,
                    fallback_message TEXT,
                    is_active BOOLEAN,
                    updated_by_user_id VARCHAR(36),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
                    error_message TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        adminId = UUID.randomUUID();
        service = new AdminAiConfigService(jdbc);
    }

    @Test
    void testAiConfigCrudAndPresets() {
        assertEquals(3, service.getPresets().size());

        CreateAiConfigDto createDto = new CreateAiConfigDto(
                "Custom Gemini Chat", "GEMINI", "gemini-1.5-flash", "CHAT",
                0.7, 2048, 5, null, 30, 100, "System prompt", "Fallback", true
        );
        AiConfigDto created = service.create(createDto, adminId);
        assertNotNull(created.id());
        assertEquals("Custom Gemini Chat", created.name());

        // Update
        UpdateAiConfigDto updateDto = new UpdateAiConfigDto(
                "Updated Gemini Chat", null, null, null, 0.5, null, null, null, null, null, null, null, null
        );
        AiConfigDto updated = service.update(UUID.fromString(created.id()), updateDto, adminId);
        assertEquals("Updated Gemini Chat", updated.name());
        assertEquals(0.5, updated.temperature());

        // List
        assertEquals(1, service.list(null, null, null).size());

        // Delete
        service.delete(UUID.fromString(created.id()));
        assertThrows(ResponseStatusException.class, () -> service.get(UUID.fromString(created.id())));
    }
}
