package vn.edu.fpt.faaps.academic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;
import vn.edu.fpt.faaps.ai.service.GeminiVisionClient;

@Component
public class TranscriptOcrParser {
    private static final long MAX_OCR_FILE_BYTES = 8_000_000L;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "application/pdf");
    private static final String OCR_PROMPT = """
            Extract all academic transcript rows from this FPT FAP grade report.
            Return ONLY a valid JSON array (no markdown fences) where each object has:
            {"courseCode":"PRF192","semesterCode":"FA2025","finalScore":"8.5","status":"PASSED","attemptNumber":1}
            Allowed status values: PASSED, FAILED, IN_PROGRESS, WITHDRAWN, EXEMPTED.
            Use empty string for missing finalScore when IN_PROGRESS.
            """;

    private final GeminiVisionClient visionClient;
    private final TranscriptWorkbookParser rowValidator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptOcrParser(
            GeminiVisionClient visionClient,
            TranscriptWorkbookParser rowValidator,
            JdbcTemplate jdbc) {
        this.visionClient = visionClient;
        this.rowValidator = rowValidator;
        this.jdbc = jdbc;
    }

    public List<ImportRow> parse(MultipartFile file) {
        String mimeType = validateFile(file);
        byte[] bytes = readBytes(file);
        String model = activeOcrModel();
        String rawText = visionClient.extractText(model, OCR_PROMPT, bytes, mimeType);
        return parseJsonRows(rawText);
    }

    List<ImportRow> parseJsonRows(String rawText) {
        String cleaned = stripCodeFences(rawText);
        JsonNode array;
        try {
            array = mapper.readTree(cleaned);
        } catch (Exception ex) {
            throw new IllegalArgumentException("OCR output could not be parsed as transcript rows", ex);
        }
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("No transcript rows detected in uploaded image or PDF");
        }
        if (array.size() > 1000) {
            throw new IllegalArgumentException("Maximum 1000 transcript rows per file");
        }

        List<ImportRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int line = 1;
        for (JsonNode item : array) {
            String course = item.path("courseCode").asText("").strip().toUpperCase(Locale.ROOT);
            String semester = item.path("semesterCode").asText("").strip().toUpperCase(Locale.ROOT);
            String score = item.path("finalScore").asText("").strip();
            String status = item.path("status").asText("PASSED").strip().toUpperCase(Locale.ROOT);
            String attempt = item.path("attemptNumber").asText("1").strip();
            rows.add(rowValidator.validateRow(line++, course, semester, score, status, attempt, keys));
        }
        return rows;
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_OCR_FILE_BYTES) {
            throw new IllegalArgumentException("Choose a PNG, JPEG, WEBP, or PDF file up to 8 MB");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported file type. Upload PNG, JPEG, WEBP, or PDF");
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded file", ex);
        }
    }

    private String activeOcrModel() {
        List<String> models = jdbc.query(
                "SELECT model_name FROM ai_configurations WHERE purpose = 'OCR' AND is_active = true LIMIT 1",
                (rs, row) -> rs.getString(1));
        return models.isEmpty() ? "gemini-2.5-flash" : models.get(0);
    }

    private static String stripCodeFences(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return trimmed.strip();
    }
}
