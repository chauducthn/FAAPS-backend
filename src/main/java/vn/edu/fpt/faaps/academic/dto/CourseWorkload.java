package vn.edu.fpt.faaps.academic.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolved 3-layer course workload assessment:
 * Layer 1 (rule-based), Layer 2 (manual admin/advisor override), Layer 3 (historical student records).
 */
public record CourseWorkload(
        String level,
        int score,
        String source,
        String manualLevel,
        String ruleLevel,
        int ruleScore,
        List<String> ruleReasons,
        HistoricalStats stats) {

    public static final String LEVEL_LIGHT = "LIGHT";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_HEAVY = "HEAVY";

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_DATA_ADJUSTED = "DATA_ADJUSTED";
    public static final String SOURCE_RULE_BASED = "RULE_BASED";

    public static final int MIN_SAMPLE_SIZE = 30;
    private static final Set<String> VALID_LEVELS = Set.of(LEVEL_LIGHT, LEVEL_MEDIUM, LEVEL_HEAVY);

    public record HistoricalStats(
            int sampleSize,
            BigDecimal failRate,
            BigDecimal avgScore,
            boolean sufficientSample,
            int dataAdjustment) {

        public static HistoricalStats empty() {
            return new HistoricalStats(0, null, null, false, 0);
        }

        public static HistoricalStats fromCounts(int sampleSize, int failedCount, BigDecimal avgScore) {
            if (sampleSize <= 0) {
                return empty();
            }
            BigDecimal failRate = BigDecimal.valueOf(failedCount)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(sampleSize), 1, RoundingMode.HALF_UP);
            BigDecimal roundedAvg = avgScore == null ? null : avgScore.setScale(2, RoundingMode.HALF_UP);
            boolean sufficient = sampleSize >= MIN_SAMPLE_SIZE;
            int adj = sufficient ? computeAdjustment(failRate, roundedAvg) : 0;
            return new HistoricalStats(sampleSize, failRate, roundedAvg, sufficient, adj);
        }

        private static int computeAdjustment(BigDecimal failRate, BigDecimal avgScore) {
            int adj = 0;
            if (failRate != null) {
                double fr = failRate.doubleValue();
                if (fr >= 35.0) adj += 20;
                else if (fr >= 25.0) adj += 14;
                else if (fr >= 15.0) adj += 7;
                else if (fr <= 5.0) adj -= 10;
                else if (fr <= 10.0) adj -= 5;
            }
            if (avgScore != null) {
                double avg = avgScore.doubleValue();
                if (avg < 6.0) adj += 10;
                else if (avg < 6.8) adj += 5;
                else if (avg >= 8.2) adj -= 8;
                else if (avg >= 7.6) adj -= 4;
            }
            return Math.max(-20, Math.min(25, adj));
        }
    }

    public record WorkloadInput(
            String courseId,
            String code,
            String name,
            int credits,
            String categoryCode,
            String categoryName,
            String knowledgeBlock,
            String gradingStructure,
            String manualLevel,
            int prerequisiteCount,
            int prerequisiteDepth,
            int unlockCount) {
    }

    public static String normalizeManualLevel(String raw) {
        if (raw == null || raw.isBlank() || "AUTO".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (!VALID_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("workloadLevel must be LIGHT, MEDIUM, HEAVY, or empty for auto");
        }
        return normalized;
    }

    public static String normalizeFilterLevel(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (!VALID_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("workloadLevel filter is invalid");
        }
        return normalized;
    }
}
