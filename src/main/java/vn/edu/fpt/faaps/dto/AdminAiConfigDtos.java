package vn.edu.fpt.faaps.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class AdminAiConfigDtos {

    public record AiConfigDto(
            String id,
            String name,
            String provider,
            String modelName,
            String purpose,
            Double temperature,
            Integer maxTokens,
            Integer retrievalTopK,
            BigDecimal similarityThreshold,
            Integer timeoutSeconds,
            Integer dailyRequestLimit,
            String systemPrompt,
            String fallbackMessage,
            boolean isActive,
            String updatedByUserId,
            String updatedAt
    ) {}

    public record CreateAiConfigDto(
            @NotBlank String name,
            @NotBlank String provider,
            @NotBlank String modelName,
            @NotBlank String purpose,
            Double temperature,
            Integer maxTokens,
            Integer retrievalTopK,
            BigDecimal similarityThreshold,
            Integer timeoutSeconds,
            Integer dailyRequestLimit,
            String systemPrompt,
            String fallbackMessage,
            Boolean isActive
    ) {}

    public record UpdateAiConfigDto(
            String name,
            String provider,
            String modelName,
            String purpose,
            Double temperature,
            Integer maxTokens,
            Integer retrievalTopK,
            BigDecimal similarityThreshold,
            Integer timeoutSeconds,
            Integer dailyRequestLimit,
            String systemPrompt,
            String fallbackMessage,
            Boolean isActive
    ) {}

    public record AiConfigSecretDto(
            @NotBlank String apiKey
    ) {}

    public record AiPresetDto(
            String id,
            String label,
            String provider,
            String modelName,
            String purpose,
            String description,
            Map<String, Object> config
    ) {}

    public record AiUsageSummaryDto(
            String configId,
            String configName,
            String purpose,
            int totalRequests,
            int successRequests,
            int failedRequests,
            int fallbackRequests,
            double averageLatencyMs,
            long totalPromptTokens,
            long totalCompletionTokens
    ) {}

    public record AiUsageByDayDto(
            String date,
            int requests,
            int failed,
            double averageLatencyMs,
            long promptTokens,
            long completionTokens
    ) {}

    public record AiPerformanceResultDto(
            List<AiUsageSummaryDto> summary,
            List<AiUsageByDayDto> byDay
    ) {}
}

