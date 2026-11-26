package vn.edu.fpt.faaps.ai.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiConfigDto;

final class AdminAiConfigMapper {

    static final String SELECT_COLUMNS = """
            SELECT id, name, provider, model_name, purpose, temperature, max_tokens,
                   retrieval_top_k, similarity_threshold, timeout_seconds, daily_request_limit,
                   system_prompt, fallback_message, is_active, updated_by_user_id, updated_at
            FROM ai_configurations
            """;

    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private AdminAiConfigMapper() {
    }

    static void appendFilters(StringBuilder where, List<Object> args,
            String purpose, String provider, String isActive, String search) {
        if (purpose != null && !purpose.isBlank() && !"ALL".equalsIgnoreCase(purpose)) {
            where.append(" AND purpose = ?");
            args.add(purpose.toUpperCase());
        }
        if (provider != null && !provider.isBlank() && !"ALL".equalsIgnoreCase(provider)) {
            where.append(" AND provider = ?");
            args.add(provider.toUpperCase());
        }
        if (isActive != null && !isActive.isBlank() && !"ALL".equalsIgnoreCase(isActive)) {
            where.append(" AND is_active = ?");
            args.add(Boolean.parseBoolean(isActive));
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(name) LIKE ? OR LOWER(model_name) LIKE ?)");
            String term = "%" + search.strip().toLowerCase() + "%";
            args.add(term);
            args.add(term);
        }
    }

    static AiConfigDto mapConfig(ResultSet rs, Map<String, String> maskedKeys, GeminiGateway gateway)
            throws SQLException {
        String id = rs.getString("id");
        String provider = rs.getString("provider");
        String storedMasked = maskedKeys == null ? null : maskedKeys.get(id);
        boolean hasEnv = gateway != null && gateway.hasEnvKey(provider);
        boolean hasKey = storedMasked != null || hasEnv;
        String displayMask = storedMasked != null ? storedMasked : (hasEnv ? "ENV key" : null);
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Double temp = rs.getObject("temperature") != null ? rs.getBigDecimal("temperature").doubleValue() : null;
        return new AiConfigDto(
                id, rs.getString("name"), provider, rs.getString("model_name"), rs.getString("purpose"),
                temp, (Integer) rs.getObject("max_tokens"), (Integer) rs.getObject("retrieval_top_k"),
                rs.getBigDecimal("similarity_threshold"), (Integer) rs.getObject("timeout_seconds"),
                (Integer) rs.getObject("daily_request_limit"), rs.getString("system_prompt"),
                rs.getString("fallback_message"), rs.getBoolean("is_active"), hasKey, displayMask,
                rs.getString("updated_by_user_id"),
                updatedTs != null ? updatedTs.toLocalDateTime().format(ISO_DT) : null);
    }
}
