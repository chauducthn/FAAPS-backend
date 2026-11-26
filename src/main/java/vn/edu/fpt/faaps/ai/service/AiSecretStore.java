package vn.edu.fpt.faaps.ai.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Write-only store for per-configuration AI provider API keys (Google AI Studio, OpenRouter). */
@Component
public class AiSecretStore {

    private final JdbcTemplate jdbc;
    private volatile boolean initialized;

    public AiSecretStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensureTable() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS ai_configuration_secrets (
                            ai_configuration_id CHAR(36) PRIMARY KEY,
                            api_key VARCHAR(512) NOT NULL,
                            updated_by_user_id CHAR(36) NULL,
                            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        )
                        """);
            } catch (Exception ignored) {
                // In-memory unit test databases may already handle DDL differently.
            }
            initialized = true;
        }
    }

    public void save(UUID configId, String apiKey, UUID actorId) {
        ensureTable();
        String key = apiKey == null ? "" : apiKey.strip();
        if (key.length() < 8) {
            throw new IllegalArgumentException("API key is too short.");
        }
        String cid = configId.toString();
        String actor = actorId == null ? null : actorId.toString();
        int updated = jdbc.update(
                "UPDATE ai_configuration_secrets SET api_key = ?, updated_by_user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE ai_configuration_id = ?",
                key, actor, cid);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO ai_configuration_secrets (ai_configuration_id, api_key, updated_by_user_id) VALUES (?, ?, ?)",
                    cid, key, actor);
        }
    }

    public String findRawKey(String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        ensureTable();
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT api_key FROM ai_configuration_secrets WHERE ai_configuration_id = ?",
                    String.class, configId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            return null;
        }
    }

    public Map<String, String> findMaskedKeys() {
        ensureTable();
        Map<String, String> masked = new HashMap<>();
        try {
            jdbc.query("SELECT ai_configuration_id, api_key FROM ai_configuration_secrets", rs -> {
                masked.put(rs.getString("ai_configuration_id"), mask(rs.getString("api_key")));
            });
        } catch (Exception ignored) {
            return masked;
        }
        return masked;
    }

    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.length() <= 4 ? "****" : "***" + trimmed.substring(trimmed.length() - 4);
    }
}
