package vn.edu.fpt.faaps.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds grounded live FPTU curriculum, transcript, policy, and conversation-memory context for AI chat & copilot. */
final class AiAcademicContextBuilder {

    private static final Pattern COURSE_CODE_RE = Pattern.compile("\\b([A-Z]{2,4}\\d{2,3}[a-z]?)\\b");

    private final JdbcTemplate jdbc;

    AiAcademicContextBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<GeminiGateway.Turn> loadConversationMemory(UUID conversationId, int maxTurns) {
        int limit = Math.max(4, Math.min(maxTurns, 24));
        try {
            return jdbc.query("""
                    SELECT sender, content FROM (
                        SELECT sender, content, created_at, id FROM ai_messages
                        WHERE conversation_id = ? AND is_fallback = FALSE
                        ORDER BY created_at DESC, id DESC LIMIT ?
                    ) t ORDER BY created_at ASC, id ASC
                    """, (rs, rowNum) -> new GeminiGateway.Turn(
                    "ASSISTANT".equalsIgnoreCase(rs.getString("sender")) ? "assistant" : "user",
                    rs.getString("content")), conversationId.toString(), limit);
        } catch (Exception ex) {
            return List.of();
        }
    }

    String buildStudentContext(UUID studentId, String userQuestion) {
        StringBuilder sb = new StringBuilder("\n\n=== LIVE FAAPS ACADEMIC CONTEXT ===\n");
        appendCoreContext(sb, studentId);
        appendMentionedCourses(sb, userQuestion);
        return sb.toString();
    }

    String buildAdvisorStudentContext(UUID studentId) {
        StringBuilder sb = new StringBuilder();
        appendCoreContext(sb, studentId);
        return sb.toString();
    }

    private void appendCoreContext(StringBuilder sb, UUID studentId) {
        String cvId = appendStudentProfile(sb, studentId);
        if (cvId != null) {
            appendCurriculumOutline(sb, cvId);
        }
        appendTranscriptSummary(sb, studentId);
        appendOpenWarnings(sb, studentId);
        appendActivePolicies(sb);
    }

    private String appendStudentProfile(StringBuilder sb, UUID studentId) {
        try {
            List<String[]> rows = jdbc.query("""
                    SELECT u.full_name, s.student_code, s.current_semester, s.current_gpa,
                           s.total_credits_earned, s.academic_status, s.curriculum_version_id,
                           m.code AS major_code, m.name AS major_name, cv.version AS cohort_version
                    FROM students s JOIN users u ON u.id = s.user_id
                    LEFT JOIN majors m ON m.id = s.major_id
                    LEFT JOIN curriculum_versions cv ON cv.id = s.curriculum_version_id
                    WHERE s.user_id = ?
                    """, (rs, rowNum) -> new String[] {
                    rs.getString("full_name"), rs.getString("student_code"),
                    String.valueOf(rs.getObject("current_semester")), String.valueOf(rs.getObject("current_gpa")),
                    String.valueOf(rs.getObject("total_credits_earned")), rs.getString("academic_status"),
                    rs.getString("curriculum_version_id"), rs.getString("major_code"),
                    rs.getString("major_name"), rs.getString("cohort_version")
            }, studentId.toString());
            if (rows.isEmpty()) {
                return null;
            }
            String[] r = rows.get(0);
            sb.append(String.format("Student: %s (%s) | Major: %s (%s) | Cohort: %s%n",
                    r[0], r[1], r[7] != null ? r[7] : "N/A", r[8] != null ? r[8] : "Unassigned", r[9] != null ? r[9] : "N/A"));
            sb.append(String.format("Current Semester: %s | GPA: %s | Credits Earned: %s | Standing: %s%n",
                    r[2], r[3], r[4], r[5]));
            return r[6];
        } catch (Exception ex) {
            return null;
        }
    }

    private void appendCurriculumOutline(StringBuilder sb, String curriculumVersionId) {
        try {
            List<String> lines = jdbc.query("""
                    SELECT cc.semester_number, c.code, c.name, c.credits, cc.is_mandatory
                    FROM curriculum_courses cc JOIN courses c ON c.id = cc.course_id
                    WHERE cc.curriculum_version_id = ? ORDER BY cc.semester_number ASC, c.code ASC LIMIT 80
                    """, (rs, rowNum) -> String.format("T%d:%s(%s,%dcr%s)",
                    rs.getInt("semester_number"), rs.getString("code"), rs.getString("name"),
                    rs.getInt("credits"), rs.getBoolean("is_mandatory") ? "" : ",elec"), curriculumVersionId);
            if (!lines.isEmpty()) {
                sb.append("Student Curriculum: ").append(String.join("; ", lines)).append("\n");
            }
        } catch (Exception ignored) {
            // Optional context.
        }
    }

    private void appendTranscriptSummary(StringBuilder sb, UUID studentId) {
        try {
            List<String> rows = jdbc.query("""
                    SELECT c.code, r.status, r.grade_point FROM student_course_records r
                    JOIN courses c ON c.id = r.course_id WHERE r.student_id = ? ORDER BY r.updated_at DESC LIMIT 50
                    """, (rs, rowNum) -> rs.getString("code") + ":" + rs.getString("status")
                    + (rs.getObject("grade_point") != null ? "(" + rs.getObject("grade_point") + ")" : ""), studentId.toString());
            if (!rows.isEmpty()) {
                sb.append("Transcript Records: ").append(String.join(", ", rows)).append("\n");
            }
        } catch (Exception ignored) {
            // Optional context.
        }
    }

    private void appendOpenWarnings(StringBuilder sb, UUID studentId) {
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

    private void appendActivePolicies(StringBuilder sb) {
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

    private void appendMentionedCourses(StringBuilder sb, String question) {
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
                        """, (rs, rowNum) -> rs.getString("code") + " - " + rs.getString("name") + " (" + rs.getInt("credits")
                        + " credits, prereqs: " + (rs.getString("prereqs") != null ? rs.getString("prereqs") : "None") + ")", code));
            } catch (Exception ignored) {
                // Skip lookup failure.
            }
        }
        if (!found.isEmpty()) {
            sb.append("Referenced Courses: ").append(String.join("; ", found)).append("\n");
        }
    }
}
