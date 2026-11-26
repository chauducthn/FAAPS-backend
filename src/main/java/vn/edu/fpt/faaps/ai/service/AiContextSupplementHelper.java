package vn.edu.fpt.faaps.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** Appends warnings, active FPTU policies, and referenced course details to AI chat context. */
final class AiContextSupplementHelper {

    private static final Pattern COURSE_CODE_RE = Pattern.compile("\\b([A-Z]{2,4}\\d{2,3}[a-z]?)\\b");

    private final JdbcTemplate jdbc;

    AiContextSupplementHelper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void appendOpenWarnings(StringBuilder sb, UUID studentId) {
        try {
            List<String> rows = jdbc.query("""
                    SELECT CONCAT(warning_type, ' [', severity, ']') FROM academic_warnings
                    WHERE student_id = ? AND resolved_at IS NULL LIMIT 5
                    """, (rs, rowNum) -> rs.getString(1), studentId.toString());
            if (!rows.isEmpty()) {
                sb.append("Open Warnings: ").append(String.join(", ", rows)).append("\n");
            }
        } catch (Exception ignored) {
            // Optional context.
        }
    }

    void appendActivePolicies(StringBuilder sb) {
        try {
            List<String> rows = jdbc.query("""
                    SELECT COALESCE(display_message, policy_type) AS title, COALESCE(description, '') AS descr
                    FROM academic_policies WHERE is_active = TRUE ORDER BY priority ASC LIMIT 10
                    """, (rs, rowNum) -> "- " + rs.getString("title") + ": " + rs.getString("descr"));
            if (!rows.isEmpty()) {
                sb.append("Active FPTU Academic Rules:\n").append(String.join("\n", rows)).append("\n");
            }
        } catch (Exception ignored) {
            // Optional context.
        }
    }

    void appendMentionedCourses(StringBuilder sb, String question) {
        if (question == null || question.isBlank()) {
            return;
        }
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = COURSE_CODE_RE.matcher(question);
        while (matcher.find() && codes.size() < 6) {
            codes.add(matcher.group(1).toUpperCase());
        }
        List<String> found = new ArrayList<>();
        for (String code : codes) {
            try {
                found.addAll(jdbc.query("""
                        SELECT c.code, c.name, c.credits,
                               (SELECT GROUP_CONCAT(p.code SEPARATOR ', ') FROM course_prerequisites cp
                                JOIN courses p ON p.id = cp.prerequisite_course_id WHERE cp.course_id = c.id) AS prereqs
                        FROM courses c WHERE UPPER(c.code) = ? LIMIT 1
                        """, (rs, rowNum) -> rs.getString("code") + " - " + rs.getString("name") + " ("
                        + rs.getInt("credits") + " credits, prereqs: "
                        + (rs.getString("prereqs") != null ? rs.getString("prereqs") : "None") + ")", code));
            } catch (Exception ignored) {
                // Skip lookup failure.
            }
        }
        if (!found.isEmpty()) {
            sb.append("Referenced Courses: ").append(String.join("; ", found)).append("\n");
        }
    }
}
