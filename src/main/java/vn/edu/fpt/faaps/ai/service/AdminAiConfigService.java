package vn.edu.fpt.faaps.ai.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigPageDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigSecretDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPerformanceResultDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPresetDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiTestResultDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.CreateAiConfigDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.UpdateAiConfigDto;

@Service
public class AdminAiConfigService {

    private final JdbcTemplate jdbc;
    private final AiSecretStore secrets;
    private final GeminiGateway gateway;

    @Autowired
    public AdminAiConfigService(JdbcTemplate jdbc, AiSecretStore secrets, GeminiGateway gateway) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.gateway = gateway;
    }

    public AdminAiConfigService(JdbcTemplate jdbc) {
        this(jdbc, new AiSecretStore(jdbc), new GeminiGateway("", new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public List<AiConfigDto> list(String purpose, String provider, String isActive) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        AdminAiConfigMapper.appendFilters(where, args, purpose, provider, isActive, null);
        Map<String, String> masked = secrets.findMaskedKeys();
        return jdbc.query(AdminAiConfigMapper.SELECT_COLUMNS + where + " ORDER BY name ASC",
                (rs, rowNum) -> AdminAiConfigMapper.mapConfig(rs, masked, gateway), args.toArray());
    }

    public AiConfigPageDto listPage(String search, String purpose, String provider, String isActive,
            int page, int size, String sort) {
        int safePage = Math.max(0, page);
        int safeSize = size < 1 ? 20 : Math.min(size, 100);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        AdminAiConfigMapper.appendFilters(where, args, purpose, provider, isActive, search);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_configurations" + where, Long.class, args.toArray());
        long totalElements = total == null ? 0 : total;
        long totalPages = (totalElements + safeSize - 1) / safeSize;
        String orderBy = "updatedAt,desc".equalsIgnoreCase(sort)
                ? " ORDER BY updated_at DESC, name ASC"
                : " ORDER BY name ASC";
        args.add(safeSize);
        args.add(safePage * safeSize);

        Map<String, String> masked = secrets.findMaskedKeys();
        List<AiConfigDto> items = jdbc.query(
                AdminAiConfigMapper.SELECT_COLUMNS + where + orderBy + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> AdminAiConfigMapper.mapConfig(rs, masked, gateway), args.toArray());
        return new AiConfigPageDto(items, safePage, safeSize, safeSize, totalElements, totalElements, totalPages);
    }

    public List<AiPresetDto> getPresets() {
        return AdminAiConfigPresets.defaultPresets();
    }

    public AiConfigDto get(UUID id) {
        Map<String, String> masked = secrets.findMaskedKeys();
        List<AiConfigDto> list = jdbc.query(AdminAiConfigMapper.SELECT_COLUMNS + " WHERE id = ?",
                (rs, rowNum) -> AdminAiConfigMapper.mapConfig(rs, masked, gateway), id.toString());
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
                                               retrieval_top_k, similarity_threshold, timeout_seconds,
                                               daily_request_limit, system_prompt, fallback_message, is_active,
                                               updated_by_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, dto.name().strip(), dto.provider().toUpperCase(), dto.modelName().strip(),
                dto.purpose().toUpperCase(),
                dto.temperature() != null ? BigDecimal.valueOf(dto.temperature()) : BigDecimal.valueOf(0.5),
                dto.maxTokens() != null ? dto.maxTokens() : 4096,
                dto.retrievalTopK() != null ? dto.retrievalTopK() : 12,
                dto.similarityThreshold(),
                dto.timeoutSeconds() != null ? dto.timeoutSeconds() : 30,
                dto.dailyRequestLimit(), dto.systemPrompt(), dto.fallbackMessage(),
                dto.isActive() != null ? dto.isActive() : true,
                actorId != null ? actorId.toString() : null);
        return get(UUID.fromString(id));
    }

    @Transactional
    public AiConfigDto update(UUID id, UpdateAiConfigDto dto, UUID actorId) {
        AiConfigDto existing = get(id);
        if (dto.name() != null && !dto.name().isBlank() && !dto.name().equalsIgnoreCase(existing.name())) {
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_configurations WHERE LOWER(name) = ? AND id <> ?",
                    Integer.class, dto.name().strip().toLowerCase(), id.toString());
            if (dup != null && dup > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A configuration with this name already exists");
            }
        }
        BigDecimal temperature = dto.temperature() != null
                ? BigDecimal.valueOf(dto.temperature())
                : (existing.temperature() != null ? BigDecimal.valueOf(existing.temperature()) : null);
        jdbc.update("""
                UPDATE ai_configurations
                SET name = ?, provider = ?, model_name = ?, purpose = ?, temperature = ?, max_tokens = ?,
                    retrieval_top_k = ?, similarity_threshold = ?, timeout_seconds = ?, daily_request_limit = ?,
                    system_prompt = ?, fallback_message = ?, is_active = ?, updated_by_user_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                dto.name() != null ? dto.name().strip() : existing.name(),
                dto.provider() != null ? dto.provider().toUpperCase() : existing.provider(),
                dto.modelName() != null ? dto.modelName().strip() : existing.modelName(),
                dto.purpose() != null ? dto.purpose().toUpperCase() : existing.purpose(),
                temperature,
                dto.maxTokens() != null ? dto.maxTokens() : existing.maxTokens(),
                dto.retrievalTopK() != null ? dto.retrievalTopK() : existing.retrievalTopK(),
                dto.similarityThreshold() != null ? dto.similarityThreshold() : existing.similarityThreshold(),
                dto.timeoutSeconds() != null ? dto.timeoutSeconds() : existing.timeoutSeconds(),
                dto.dailyRequestLimit() != null ? dto.dailyRequestLimit() : existing.dailyRequestLimit(),
                dto.systemPrompt() != null ? dto.systemPrompt() : existing.systemPrompt(),
                dto.fallbackMessage() != null ? dto.fallbackMessage() : existing.fallbackMessage(),
                dto.isActive() != null ? dto.isActive() : existing.isActive(),
                actorId != null ? actorId.toString() : null, id.toString());
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        int rows = jdbc.update("DELETE FROM ai_configurations WHERE id = ?", id.toString());
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI Configuration not found");
        }
    }

    @Transactional
    public void setSecret(UUID id, AiConfigSecretDto secretDto, UUID actorId) {
        get(id);
        secrets.save(id, secretDto.apiKey(), actorId);
    }

    public AiTestResultDto testConnection(UUID id, UUID actorId) {
        AiConfigDto cfg = get(id);
        String rawKey = secrets.findRawKey(cfg.id());
        long started = System.nanoTime();
        GeminiGateway.Reply reply = gateway.generate(new GeminiGateway.Request(
                cfg.provider(), cfg.modelName(), rawKey,
                "Reply in one short sentence confirming FAAPS AI connectivity.",
                List.of(), "Ping from FAAPS Admin.", cfg.temperature(), 128, cfg.timeoutSeconds()));
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
        try {
            jdbc.update("""
                    INSERT INTO ai_usage_logs (id, user_id, ai_configuration_id, feature, prompt_tokens,
                                               completion_tokens, total_tokens, latency_ms, status, error_message)
                    VALUES (?, ?, ?, 'CHAT', ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), actorId == null ? null : actorId.toString(), cfg.id(),
                    reply.promptTokens(), reply.completionTokens(), reply.promptTokens() + reply.completionTokens(),
                    latencyMs, reply.fallback() ? "ERROR" : "SUCCESS", reply.errorMessage());
        } catch (Exception ignored) {
            // Keep test resilient if optional column differs.
        }
        String preview = reply.text() == null ? null : reply.text().strip();
        if (preview != null && preview.length() > 160) {
            preview = preview.substring(0, 160) + "...";
        }
        return new AiTestResultDto(!reply.fallback(), latencyMs, cfg.provider(), cfg.modelName(),
                preview, reply.errorMessage());
    }

    public AiPerformanceResultDto getPerformance(UUID configId, String purpose, String startDate, String endDate) {
        return AdminAiPerformanceQuery.query(jdbc, configId, purpose, startDate, endDate);
    }
}
