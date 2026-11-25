package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiVisionClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiVisionClient.class);
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final HttpClient client;
    private final ObjectMapper json;
    private final String apiKey;
    private final URI baseUrl;

    @Autowired
    public GeminiVisionClient(@Value("${faaps.ai.gemini-api-key:}") String apiKey) {
        this(apiKey, new ObjectMapper(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                URI.create("https://generativelanguage.googleapis.com/v1beta/models/"));
    }

    public GeminiVisionClient(String apiKey, ObjectMapper json, HttpClient client, URI baseUrl) {
        this.apiKey = apiKey;
        this.json = json;
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String extractText(String model, String prompt, byte[] fileBytes, String mimeType) {
        if (!isConfigured()) {
            throw new IllegalStateException("Gemini OCR is not configured on the server");
        }
        String selectedModel = resolveModel(model);
        String encoded = Base64.getEncoder().encodeToString(fileBytes);
        Map<String, Object> payload = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("text", prompt),
                                Map.of("inline_data", Map.of("mime_type", mimeType, "data", encoded))))));
        try {
            String body = json.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + selectedModel + ":generateContent"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Gemini OCR returned HTTP {}", response.statusCode());
                throw new IllegalStateException("OCR provider returned an error");
            }
            return parseCandidateText(response.body());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Gemini OCR request failed: {}", ex.getMessage());
            throw new IllegalStateException("Unable to process transcript with OCR", ex);
        }
    }

    private String parseCandidateText(String responseBody) throws Exception {
        JsonNode root = json.readTree(responseBody);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder result = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("text").isTextual()) {
                result.append(part.path("text").asText());
            }
        }
        String text = result.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("OCR returned no readable transcript content");
        }
        return text;
    }

    private static String resolveModel(String model) {
        if (model != null && model.matches("gemini-[a-zA-Z0-9.-]+") && !model.startsWith("gemini-1.")) {
            return model;
        }
        return DEFAULT_MODEL;
    }
}
