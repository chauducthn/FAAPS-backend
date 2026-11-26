package vn.edu.fpt.faaps.ai.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPerformanceResultDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiUsageByDayDto;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiUsageSummaryDto;

final class AdminAiPerformanceQuery {

    private AdminAiPerformanceQuery() {
    }

    static AiPerformanceResultDto query(JdbcTemplate jdbc, UUID configId, String purpose,
            String startDate, String endDate) {
        List<AiUsageSummaryDto> summaryList = querySummary(jdbc, configId, purpose, startDate, endDate);
        List<AiUsageByDayDto> dailyList = queryDaily(jdbc, configId, purpose, startDate, endDate);
        return new AiPerformanceResultDto(summaryList, dailyList);
    }

    private static List<AiUsageSummaryDto> querySummary(JdbcTemplate jdbc, UUID configId, String purpose,
            String startDate, String endDate) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id AS config_id, c.name AS config_name, c.purpose,
                       COUNT(l.id) AS total_requests,
                       COALESCE(SUM(CASE WHEN l.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_requests,
                       COALESCE(SUM(CASE WHEN l.status IN ('ERROR', 'TIMEOUT', 'RATE_LIMITED') THEN 1 ELSE 0 END), 0)
                           AS failed_requests,
                       COALESCE(SUM(CASE WHEN l.status = 'FALLBACK' THEN 1 ELSE 0 END), 0) AS fallback_requests,
                       COALESCE(AVG(l.latency_ms), 0) AS avg_latency_ms,
                       COALESCE(SUM(l.prompt_tokens), 0) AS total_prompt_tokens,
                       COALESCE(SUM(l.completion_tokens), 0) AS total_completion_tokens
                FROM ai_configurations c
                LEFT JOIN ai_usage_logs l ON l.ai_configuration_id = c.id
                """);
        List<Object> args = new ArrayList<>();
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND DATE(l.created_at) >= ?");
            args.add(startDate.strip());
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND DATE(l.created_at) <= ?");
            args.add(endDate.strip());
        }
        sql.append(" WHERE 1 = 1");
        if (configId != null) {
            sql.append(" AND c.id = ?");
            args.add(configId.toString());
        }
        if (purpose != null && !purpose.isBlank() && !"ALL".equalsIgnoreCase(purpose)) {
            sql.append(" AND c.purpose = ?");
            args.add(purpose.toUpperCase());
        }
        sql.append(" GROUP BY c.id, c.name, c.purpose ORDER BY c.name ASC");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AiUsageSummaryDto(
                rs.getString("config_id"),
                rs.getString("config_name"),
                rs.getString("purpose"),
                rs.getInt("total_requests"),
                rs.getInt("success_requests"),
                rs.getInt("failed_requests"),
                rs.getInt("fallback_requests"),
                rs.getDouble("avg_latency_ms"),
                rs.getLong("total_prompt_tokens"),
                rs.getLong("total_completion_tokens")), args.toArray());
    }

    private static List<AiUsageByDayDto> queryDaily(JdbcTemplate jdbc, UUID configId, String purpose,
            String startDate, String endDate) {
        StringBuilder sql = new StringBuilder("""
                SELECT DATE(l.created_at) AS log_date,
                       COUNT(l.id) AS requests,
                       COALESCE(SUM(CASE WHEN l.status IN ('ERROR', 'TIMEOUT', 'RATE_LIMITED') THEN 1 ELSE 0 END), 0) AS failed,
                       COALESCE(AVG(l.latency_ms), 0) AS avg_latency_ms,
                       COALESCE(SUM(l.prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(l.completion_tokens), 0) AS completion_tokens
                FROM ai_usage_logs l
                LEFT JOIN ai_configurations c ON c.id = l.ai_configuration_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (configId != null) {
            sql.append(" AND l.ai_configuration_id = ?");
            args.add(configId.toString());
        }
        if (purpose != null && !purpose.isBlank() && !"ALL".equalsIgnoreCase(purpose)) {
            sql.append(" AND c.purpose = ?");
            args.add(purpose.toUpperCase());
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND DATE(l.created_at) >= ?");
            args.add(startDate.strip());
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND DATE(l.created_at) <= ?");
            args.add(endDate.strip());
        }
        sql.append(" GROUP BY DATE(l.created_at) ORDER BY log_date ASC");

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            java.sql.Date d = rs.getDate("log_date");
            return new AiUsageByDayDto(
                    d != null ? d.toString() : LocalDate.now().toString(),
                    rs.getInt("requests"),
                    rs.getInt("failed"),
                    rs.getDouble("avg_latency_ms"),
                    rs.getLong("prompt_tokens"),
                    rs.getLong("completion_tokens"));
        }, args.toArray());
    }
}
