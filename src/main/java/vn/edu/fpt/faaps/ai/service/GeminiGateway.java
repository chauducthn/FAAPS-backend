package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class GeminiGateway {
    private final HttpClient client;
    private final ObjectMapper json;
    private final String key;
    private final URI baseUrl;

    @Autowired
    GeminiGateway(@Value("${faaps.ai.gemini-api-key:}") String key) {
        this(key, new ObjectMapper(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://generativelanguage.googleapis.com/v1beta/models/"));
    }

    GeminiGateway(String key, ObjectMapper json) {
        this(key, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://generativelanguage.googleapis.com/v1beta/models/"));
    }

    GeminiGateway(String key, ObjectMapper json, HttpClient client, URI baseUrl) {
        this.key = key;
        this.json = json;
        this.client = client;
        this.baseUrl = baseUrl;
    }

    Reply generate(String model, String systemPrompt, String question) {
        if (key == null || key.isBlank()) return Reply.unavailable();
        String selectedModel = model != null && model.matches("gemini-[a-zA-Z0-9.-]+")
                && !model.startsWith("gemini-1.")
                ? model : "gemini-2.5-flash";
        try {
            String body = json.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", new Object[] { Map.of("text", systemPrompt == null ? "" : systemPrompt) }),
                    "contents", new Object[] { Map.of("role", "user", "parts", new Object[] { Map.of("text", question) }) }));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + selectedModel + ":generateContent"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Reply.unavailable();
            JsonNode root = json.readTree(response.body());
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            StringBuilder answer = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.path("text").isTextual()) answer.append(part.path("text").asText());
            }
            if (answer.isEmpty()) return Reply.unavailable();
            JsonNode usage = root.path("usageMetadata");
            return new Reply(answer.toString(), false,
                    usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0));
        } catch (Exception e) {
            return Reply.unavailable();
        }
    }

    record Reply(String text, boolean fallback, int promptTokens, int completionTokens) {
        static Reply unavailable() { return new Reply(null, true, 0, 0); }
    }
}
