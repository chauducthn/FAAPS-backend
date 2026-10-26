package vn.edu.fpt.faaps.admin.service;

import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiConfigDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiConfigSecretDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiPerformanceResultDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiPresetDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiUsageByDayDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.AiUsageSummaryDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.admin.dto.AdminAiConfigDtos.UpdateAiConfigDto;

@Service
public class AdminAiConfigService {

    private final JdbcTemplate jdbc;
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public AdminAiConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiConfigDto> list(String purpose, String provider, String isActive) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, provider, model_name, purpose, temperature, max_tokens,
                       retrieval_top_k, similarity_threshold, timeout_seconds, daily_request_limit,
                       system_prompt, fallback_message, is_active, updated_by_user_id, updated_at
                FROM ai_configurations WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();

        if (purpose != null && !purpose.isBlank() && !"ALL".equalsIgnoreCase(purpose)) {
            sql.append(" AND purpose = ?");
            args.add(purpose.toUpperCase());
        }
        if (provider != null && !provider.isBlank() && !"ALL".equalsIgnoreCase(provider)) {
            sql.append(" AND provider = ?");
            args.add(provider.toUpperCase());
        }
        if (isActive != null && !isActive.isBlank() && !"ALL".equalsIgnoreCase(isActive)) {
            sql.append(" AND is_active = ?");
            args.add(Boolean.parseBoolean(isActive));
        }

        sql.append(" ORDER BY name ASC");
        return jdbc.query(sql.toString(), (rs, rowNum) -> mapConfig(rs), args.toArray());
    }

    public List<AiPresetDto> getPresets() {
        return List.of(
                new AiPresetDto(
                        "gemini-flash-chat",
                        "Gemini 1.5 Flash - Student Advisor Chat",
                        "GEMINI",
                        "gemini-1.5-flash",
                        "CHAT",
                        "Fast, responsive conversational model for academic guidance and policy questions.",
                        Map.of(
                                "temperature", 0.7,
                                "maxTokens", 2048,
                                "retrievalTopK", 5,
                                "similarityThreshold", 0.75,
                                "timeoutSeconds", 30,
                                "dailyRequestLimit", 100,
                                "systemPrompt", "You are an AI academic advisor for FAAPS. Provide clear, empathetic, and accurate advice grounded strictly in university regulations.",
                                "fallbackMessage", "The advising assistant is currently unavailable. Please try again shortly or contact your advisor."
                        )
                ),
                new AiPresetDto(
                        "gemini-pro-roadmap",
                        "Gemini 1.5 Pro - Academic Roadmap Generation",
                        "GEMINI",
                        "gemini-1.5-pro",
                        "CHAT",
                        "High reasoning model for prerequisite DAG resolution and semester curriculum roadmaps.",
                        Map.of(
                                "temperature", 0.2,
                                "maxTokens", 4096,
                                "retrievalTopK", 10,
                                "similarityThreshold", 0.80,
                                "timeoutSeconds", 60,
                                "dailyRequestLimit", 30,
                                "systemPrompt", "You are an expert curriculum roadmap planner. Given student transcripts and curriculum DAG, output an optimal study roadmap.",
                                "fallbackMessage", "Roadmap generation is currently unavailable. Please consult your academic advisor."
                        )
                ),
                new AiPresetDto(
                        "text-embedding-004",
                        "Text Embedding 004 - RAG Document Retrieval",
                        "GEMINI",
                        "text-embedding-004",
                        "EMBEDDING",
                        "Vector embedding model for semantic search over student handbooks and university regulations.",
                        Map.of(
                                "temperature", 0.0,
                                "maxTokens", 1024,
                                "retrievalTopK", 5,
                                "similarityThreshold", 0.70,
                                "timeoutSeconds", 15,
                                "dailyRequestLimit", 500,
                                "systemPrompt", "",
                                "fallbackMessage", ""
                        )
                )
        );
    }

    public AiConfigDto get(UUID id) {
        List<AiConfigDto> list = jdbc.query("""
                SELECT id, name, provider, model_name, purpose, temperature, max_tokens,
                       retrieval_top_k, similarity_threshold, timeout_seconds, daily_request_limit,
                       system_prompt, fallback_message, is_active, updated_by_user_id, updated_at
                FROM ai_configurations WHERE id = ?
                """, (rs, rowNum) -> mapConfig(rs), id.toString());
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI Configuration not found");
        }
        return list.get(0);
    }

    @Transactional
    public AiConfigDto create(CreateAiConfigDto dto, UUID actorId) {
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ai_configurations WHERE LOWER(name) = ?",
                Integer.class, dto.name().strip().toLowerCase());
        if (dup != null && dup > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A configuration with this name already exists");
        }

        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ai_configurations (id, name, provider, model_name, purpose, temperature, max_tokens,
                                               retrieval_top_k, similarity_threshold, timeout_seconds, daily_request_limit,
                                               system_prompt, fallback_message, is_active, updated_by_user_id,
                                               created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, dto.name().strip(), dto.provider().toUpperCase(), dto.modelName().strip(),
                dto.purpose().toUpperCase(),
                dto.temperature() != null ? BigDecimal.valueOf(dto.temperature()) : BigDecimal.valueOf(0.7),
                dto.maxTokens() != null ? dto.maxTokens() : 2048,
                dto.retrievalTopK() != null ? dto.retrievalTopK() : 5,
                dto.similarityThreshold(),
                dto.timeoutSeconds() != null ? dto.timeoutSeconds() : 30,
                dto.dailyRequestLimit(),
                dto.systemPrompt(), dto.fallbackMessage(),
                dto.isActive() != null ? dto.isActive() : true,
                actorId != null ? actorId.toString() : null);

        return get(UUID.fromString(id));
    }

    @Transactional
    public AiConfigDto update(UUID id, UpdateAiConfigDto dto, UUID actorId) {
        AiConfigDto existing = get(id);

        if (dto.name() != null && !dto.name().isBlank() && !dto.name().equalsIgnoreCase(existing.name())) {
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ai_configurations WHERE LOWER(name) = ? AND id <> ?",
                    Integer.class, dto.name().strip().toLowerCase(), id.toString());
            if (dup != null && dup > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A configuration with this name already exists");
            }
        }

        String name = dto.name() != null ? dto.name().strip() : existing.name();
        String provider = dto.provider() != null ? dto.provider().toUpperCase() : existing.provider();
        String modelName = dto.modelName() != null ? dto.modelName().strip() : existing.modelName();
        String purpose = dto.purpose() != null ? dto.purpose().toUpperCase() : existing.purpose();
        BigDecimal temperature = dto.temperature() != null ? BigDecimal.valueOf(dto.temperature()) : (existing.temperature() != null ? BigDecimal.valueOf(existing.temperature()) : null);
        Integer maxTokens = dto.maxTokens() != null ? dto.maxTokens() : existing.maxTokens();
        Integer retrievalTopK = dto.retrievalTopK() != null ? dto.retrievalTopK() : existing.retrievalTopK();
        BigDecimal similarityThreshold = dto.similarityThreshold() != null ? dto.similarityThreshold() : existing.similarityThreshold();
        Integer timeoutSeconds = dto.timeoutSeconds() != null ? dto.timeoutSeconds() : existing.timeoutSeconds();
        Integer dailyRequestLimit = dto.dailyRequestLimit() != null ? dto.dailyRequestLimit() : existing.dailyRequestLimit();
        String systemPrompt = dto.systemPrompt() != null ? dto.systemPrompt() : existing.systemPrompt();
        String fallbackMessage = dto.fallbackMessage() != null ? dto.fallbackMessage() : existing.fallbackMessage();
        boolean isActive = dto.isActive() != null ? dto.isActive() : existing.isActive();

        jdbc.update("""
                UPDATE ai_configurations
                SET name = ?, provider = ?, model_name = ?, purpose = ?, temperature = ?, max_tokens = ?,
                    retrieval_top_k = ?, similarity_threshold = ?, timeout_seconds = ?, daily_request_limit = ?,
                    system_prompt = ?, fallback_message = ?, is_active = ?, updated_by_user_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, name, provider, modelName, purpose, temperature, maxTokens, retrievalTopK,
                similarityThreshold, timeoutSeconds, dailyRequestLimit, systemPrompt, fallbackMessage,
                isActive, actorId != null ? actorId.toString() : null, id.toString());

        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        int rows = jdbc.update("DELETE FROM ai_configurations WHERE id = ?", id.toString());
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI Configuration not found");
        }
    }

    public void setSecret(UUID id, AiConfigSecretDto secretDto, UUID actorId) {
        get(id);
        if (secretDto.apiKey() == null || secretDto.apiKey().isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        jdbc.update("UPDATE ai_configurations SET updated_by_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                actorId != null ? actorId.toString() : null, id.toString());
    }

    public AiPerformanceResultDto getPerformance(UUID configId, String purpose, String startDate, String endDate) {
        StringBuilder summarySql = new StringBuilder("""
                SELECT c.id AS config_id, c.name AS config_name, c.purpose,
                       COUNT(l.id) AS total_requests,
                       SUM(CASE WHEN l.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_requests,
                       SUM(CASE WHEN l.status IN ('ERROR', 'TIMEOUT', 'RATE_LIMITED') THEN 1 ELSE 0 END) AS failed_requests,
                       SUM(CASE WHEN l.status = 'FALLBACK' THEN 1 ELSE 0 END) AS fallback_requests,
                       AVG(COALESCE(l.latency_ms, 0)) AS avg_latency_ms,
                       SUM(COALESCE(l.prompt_tokens, 0)) AS total_prompt_tokens,
                       SUM(COALESCE(l.completion_tokens, 0)) AS total_completion_tokens
                FROM ai_configurations c
                LEFT JOIN ai_usage_logs l ON l.ai_configuration_id = c.id
                WHERE 1 = 1
                """);
        List<Object> summaryArgs = new ArrayList<>();

        if (configId != null) {
            summarySql.append(" AND c.id = ?");
            summaryArgs.add(configId.toString());
        }
        if (purpose != null && !purpose.isBlank() && !"ALL".equalsIgnoreCase(purpose)) {
            summarySql.append(" AND c.purpose = ?");
            summaryArgs.add(purpose.toUpperCase());
        }

        summarySql.append(" GROUP BY c.id, c.name, c.purpose ORDER BY c.name ASC");

        List<AiUsageSummaryDto> summaryList = jdbc.query(summarySql.toString(), (rs, rowNum) -> new AiUsageSummaryDto(
                rs.getString("config_id"),
                rs.getString("config_name"),
                rs.getString("purpose"),
                rs.getInt("total_requests"),
                rs.getInt("success_requests"),
                rs.getInt("failed_requests"),
                rs.getInt("fallback_requests"),
                rs.getDouble("avg_latency_ms"),
                rs.getLong("total_prompt_tokens"),
                rs.getLong("total_completion_tokens")
        ), summaryArgs.toArray());

        StringBuilder dailySql = new StringBuilder("""
                SELECT DATE(created_at) AS log_date,
                       COUNT(id) AS requests,
                       SUM(CASE WHEN status IN ('ERROR', 'TIMEOUT', 'RATE_LIMITED') THEN 1 ELSE 0 END) AS failed,
                       AVG(COALESCE(latency_ms, 0)) AS avg_latency_ms,
                       SUM(COALESCE(prompt_tokens, 0)) AS prompt_tokens,
                       SUM(COALESCE(completion_tokens, 0)) AS completion_tokens
                FROM ai_usage_logs
                WHERE 1 = 1
                """);
        List<Object> dailyArgs = new ArrayList<>();

        if (configId != null) {
            dailySql.append(" AND ai_configuration_id = ?");
            dailyArgs.add(configId.toString());
        }
        if (startDate != null && !startDate.isBlank()) {
            dailySql.append(" AND created_at >= ?");
            dailyArgs.add(startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            dailySql.append(" AND created_at <= ?");
            dailyArgs.add(endDate);
        }

        dailySql.append(" GROUP BY DATE(created_at) ORDER BY log_date ASC");

        List<AiUsageByDayDto> dailyList = jdbc.query(dailySql.toString(), (rs, rowNum) -> {
            java.sql.Date d = rs.getDate("log_date");
            return new AiUsageByDayDto(
                    d != null ? d.toString() : LocalDate.now().toString(),
                    rs.getInt("requests"),
                    rs.getInt("failed"),
                    rs.getDouble("avg_latency_ms"),
                    rs.getLong("prompt_tokens"),
                    rs.getLong("completion_tokens")
            );
        }, dailyArgs.toArray());

        return new AiPerformanceResultDto(summaryList, dailyList);
    }

    private AiConfigDto mapConfig(ResultSet rs) throws SQLException {
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Double temp = rs.getObject("temperature") != null ? rs.getBigDecimal("temperature").doubleValue() : null;
        return new AiConfigDto(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("provider"),
                rs.getString("model_name"),
                rs.getString("purpose"),
                temp,
                (Integer) rs.getObject("max_tokens"),
                (Integer) rs.getObject("retrieval_top_k"),
                rs.getBigDecimal("similarity_threshold"),
                (Integer) rs.getObject("timeout_seconds"),
                (Integer) rs.getObject("daily_request_limit"),
                rs.getString("system_prompt"),
                rs.getString("fallback_message"),
                rs.getBoolean("is_active"),
                rs.getString("updated_by_user_id"),
                updatedTs != null ? updatedTs.toLocalDateTime().format(ISO_DT) : null
        );
    }
}
