package vn.edu.fpt.faaps.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds grounded live FPTU curriculum, transcript, strength/weakness, and data-gap context for AI chat. */
final class AiAcademicContextBuilder {

    private final JdbcTemplate jdbc;
    private final AiContextSupplementHelper supplements;

    AiAcademicContextBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.supplements = new AiContextSupplementHelper(jdbc);
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
        supplements.appendMentionedCourses(sb, userQuestion);
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
        int transcriptCount = appendTranscriptAnalysis(sb, studentId);
        supplements.appendOpenWarnings(sb, studentId);
        appendDataCompleteness(sb, cvId, transcriptCount);
        supplements.appendActivePolicies(sb);
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
                    r[0], r[1], r[7] != null ? r[7] : "N/A",
                    r[8] != null ? r[8] : "Unassigned", r[9] != null ? r[9] : "N/A"));
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

    private int appendTranscriptAnalysis(StringBuilder sb, UUID studentId) {
        try {
            List<String> all = new ArrayList<>();
            List<String> strong = new ArrayList<>();
            List<String> weak = new ArrayList<>();
            jdbc.query("""
                    SELECT c.code, c.name, r.status, r.grade_point
                    FROM student_course_records r JOIN courses c ON c.id = r.course_id
                    WHERE r.student_id = ? ORDER BY r.updated_at DESC LIMIT 60
                    """, rs -> {
                String code = rs.getString("code");
                String status = rs.getString("status");
                Object gpObj = rs.getObject("grade_point");
                Double gp = gpObj instanceof Number n ? n.doubleValue() : null;
                String entry = code + ":" + status + (gp != null ? "(" + gp + ")" : "");
                all.add(entry);
                if ("FAILED".equalsIgnoreCase(status) || (gp != null && gp < 6.0)) {
                    weak.add(entry);
                } else if (gp != null && gp >= 8.0) {
                    strong.add(entry);
                }
            }, studentId.toString());
            if (!all.isEmpty()) {
                sb.append("Transcript Records (").append(all.size()).append("): ")
                        .append(String.join(", ", all)).append("\n");
            }
            if (!strong.isEmpty()) {
                sb.append("Strong Subjects (>=8.0): ").append(String.join(", ", strong)).append("\n");
            }
            if (!weak.isEmpty()) {
                sb.append("Weak / Failed Subjects (<6.0 or FAILED): ").append(String.join(", ", weak)).append("\n");
            }
            return all.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void appendDataCompleteness(StringBuilder sb, String curriculumVersionId, int transcriptCount) {
        List<String> missing = new ArrayList<>();
        if (curriculumVersionId == null || curriculumVersionId.isBlank()) {
            missing.add("Major & Curriculum not configured (direct student to /student/curriculum-goals)");
        }
        if (transcriptCount == 0) {
            missing.add("No transcript/grades uploaded yet (suggest uploading at /student/transcript or sharing courses/scores in chat)");
        }
        if (!missing.isEmpty()) {
            sb.append("MISSING STUDENT DATA: ").append(String.join("; ", missing)).append("\n");
        }
    }
}
