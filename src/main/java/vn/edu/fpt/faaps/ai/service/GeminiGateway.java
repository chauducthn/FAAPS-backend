package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dual-provider AI gateway supporting Google AI Studio (Gemini) and OpenRouter with multi-turn memory. */
@Component
public class GeminiGateway {

    private static final URI GEMINI_BASE = URI.create("https://generativelanguage.googleapis.com/v1beta/models/");
    private static final URI OPENROUTER_URL = URI.create("https://openrouter.ai/api/v1/chat/completions");

    private final HttpClient client;
    private final ObjectMapper json;
    private final String geminiEnvKey;
    private final String openRouterEnvKey;
    private final URI geminiBaseUrl;

    @Autowired
    GeminiGateway(@Value("${faaps.ai.gemini-api-key:}") String geminiEnvKey,
            @Value("${faaps.ai.openrouter-api-key:}") String openRouterEnvKey) {
        this(geminiEnvKey, openRouterEnvKey, new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), GEMINI_BASE);
    }

    GeminiGateway(String geminiEnvKey, ObjectMapper json) {
        this(geminiEnvKey, "", json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), GEMINI_BASE);
    }

    GeminiGateway(String geminiEnvKey, ObjectMapper json, HttpClient client, URI geminiBaseUrl) {
        this(geminiEnvKey, "", json, client, geminiBaseUrl);
    }

    private GeminiGateway(String geminiEnvKey, String openRouterEnvKey, ObjectMapper json, HttpClient client, URI geminiBaseUrl) {
        this.geminiEnvKey = geminiEnvKey;
        this.openRouterEnvKey = openRouterEnvKey;
        this.json = json;
        this.client = client;
        this.geminiBaseUrl = geminiBaseUrl;
    }

    Reply generate(String model, String systemPrompt, String question) {
        return generate(new Request("GEMINI", model, null, systemPrompt, List.of(), question, null, null, 30));
    }

    Reply generate(Request req) {
        String provider = req.provider() == null ? "GEMINI" : req.provider().strip().toUpperCase(Locale.ROOT);
        return "OPENROUTER".equals(provider) ? callOpenRouter(req) : callGemini(req);
    }

    boolean hasEnvKey(String provider) {
        String p = provider == null ? "" : provider.strip().toUpperCase(Locale.ROOT);
        return "OPENROUTER".equals(p)
                ? openRouterEnvKey != null && !openRouterEnvKey.isBlank()
                : geminiEnvKey != null && !geminiEnvKey.isBlank();
    }

    private Reply callGemini(Request req) {
        String apiKey = firstNonBlank(req.apiKeyOverride(), geminiEnvKey);
        if (apiKey == null) {
            return Reply.unavailable("Google AI Studio API key is not configured.");
        }
        String model = req.model() != null && req.model().matches("gemini-[a-zA-Z0-9.-]+")
                && !req.model().startsWith("gemini-1.") ? req.model() : "gemini-2.5-flash";
        try {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (Turn t : req.history()) {
                contents.add(Map.of("role", "assistant".equals(t.role()) ? "model" : "user", "parts", List.of(Map.of("text", t.content()))));
            }
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", req.question()))));
            Map<String, Object> payload = new LinkedHashMap<>();
            if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", req.systemPrompt()))));
            }
            payload.put("contents", contents);
            Map<String, Object> genCfg = new LinkedHashMap<>();
            if (req.temperature() != null) genCfg.put("temperature", req.temperature());
            if (req.maxTokens() != null && req.maxTokens() > 0) genCfg.put("maxOutputTokens", req.maxTokens());
            if (!genCfg.isEmpty()) payload.put("generationConfig", genCfg);

            HttpRequest http = HttpRequest.newBuilder(URI.create(geminiBaseUrl + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(safeTimeout(req.timeoutSeconds())))
                    .header("Content-Type", "application/json").header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Reply.unavailable("Google AI Studio HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            StringBuilder answer = new StringBuilder();
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                if (part.path("text").isTextual()) answer.append(part.path("text").asText());
            }
            if (answer.isEmpty()) {
                return Reply.unavailable("Empty response from Google AI Studio.");
            }
            JsonNode usage = root.path("usageMetadata");
            return new Reply(answer.toString(), false,
                    usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0), null);
        } catch (Exception ex) {
            return Reply.unavailable("Google AI Studio error: " + ex.getMessage());
        }
    }

    private Reply callOpenRouter(Request req) {
        String apiKey = firstNonBlank(req.apiKeyOverride(), openRouterEnvKey);
        if (apiKey == null) {
            return Reply.unavailable("OpenRouter API key is not configured.");
        }
        String model = req.model() != null && !req.model().isBlank() ? req.model().strip() : "google/gemini-2.5-flash";
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", req.systemPrompt()));
            }
            for (Turn t : req.history()) {
                messages.add(Map.of("role", "assistant".equals(t.role()) ? "assistant" : "user", "content", t.content()));
            }
            messages.add(Map.of("role", "user", "content", req.question()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            if (req.temperature() != null) payload.put("temperature", req.temperature());
            if (req.maxTokens() != null && req.maxTokens() > 0) payload.put("max_tokens", req.maxTokens());

            HttpRequest http = HttpRequest.newBuilder(OPENROUTER_URL)
                    .timeout(Duration.ofSeconds(safeTimeout(req.timeoutSeconds())))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://faaps.fpt.edu.vn").header("X-Title", "FAAPS Academic Advising")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Reply.unavailable("OpenRouter HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                return Reply.unavailable("Empty response from OpenRouter.");
            }
            JsonNode usage = root.path("usage");
            return new Reply(text, false,
                    usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), null);
        } catch (Exception ex) {
            return Reply.unavailable("OpenRouter error: " + ex.getMessage());
        }
    }

    private static int safeTimeout(Integer seconds) {
        return seconds == null || seconds < 5 ? 30 : Math.min(seconds, 120);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.strip() : (b != null && !b.isBlank() ? b.strip() : null);
    }

    record Turn(String role, String content) {}

    record Request(String provider, String model, String apiKeyOverride, String systemPrompt,
            List<Turn> history, String question, Double temperature, Integer maxTokens, Integer timeoutSeconds) {}

    record Reply(String text, boolean fallback, int promptTokens, int completionTokens, String errorMessage) {
        Reply(String text, boolean fallback, int promptTokens, int completionTokens) {
            this(text, fallback, promptTokens, completionTokens, null);
        }

        static Reply unavailable() {
            return new Reply(null, true, 0, 0, "AI provider unavailable");
        }

        static Reply unavailable(String error) {
            return new Reply(null, true, 0, 0, error);
        }
    }
}
