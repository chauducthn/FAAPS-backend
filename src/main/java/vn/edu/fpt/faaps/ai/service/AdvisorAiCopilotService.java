package vn.edu.fpt.faaps.ai.service;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdvisorAiCopilotService {
    private static final String BRIEF_SYSTEM_PROMPT = """
            You are an Academic Advising Copilot for FPT University advisors.
            Given a student's academic summary, FLM curriculum, failed courses, and open warnings,
            produce a concise, actionable advising brief with:
            1. Risk & Performance Summary
            2. Recommended Discussion Points (3-4 bullets)
            3. Suggested Next Steps & Course Plan for the next semester.
            Keep the response structured, factual, and under 280 words.
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are an Academic Advising Copilot. Turn the advisor's raw meeting notes into a clear,
            professional advising session summary with:
            - Key Discussion Points
            - Agreed Action Items
            - Follow-up Plan.
            Keep it concise and factual.
            """;

    private final JdbcTemplate jdbc;
    private final GeminiGateway gemini;
    private final AiSecretStore secrets;
    private final AiAcademicContextBuilder contextBuilder;
    private final StudentAiChatSupport chatSupport;

    @Autowired
    public AdvisorAiCopilotService(JdbcTemplate jdbc, GeminiGateway gemini, AiSecretStore secrets) {
        this.jdbc = jdbc;
        this.gemini = gemini;
        this.secrets = secrets;
        this.contextBuilder = new AiAcademicContextBuilder(jdbc);
        this.chatSupport = new StudentAiChatSupport(jdbc);
    }

    AdvisorAiCopilotService(JdbcTemplate jdbc, GeminiGateway gemini) {
        this(jdbc, gemini, new AiSecretStore(jdbc));
    }

    public CopilotResult generateStudentBrief(UUID advisorId, UUID studentId) {
        StudentSnapshot snapshot = loadStudentSnapshot(studentId);
        String prompt = buildBriefPrompt(snapshot) + "\n" + contextBuilder.buildAdvisorStudentContext(studentId);
        return executeCopilot(advisorId, BRIEF_SYSTEM_PROMPT, prompt, fallbackBrief(snapshot));
    }

    public CopilotResult draftSessionSummary(UUID advisorId, UUID sessionId, String rawNotes) {
        if (rawNotes == null || rawNotes.isBlank()) {
            throw new IllegalArgumentException("rawNotes is required");
        }
        String agenda = loadSessionAgenda(advisorId, sessionId);
        String prompt = "Session Agenda: " + (agenda == null ? "Academic Advising" : agenda)
                + "\nAdvisor Raw Notes:\n" + rawNotes.strip();
        String fallback = "Session Summary:\n- " + rawNotes.strip()
                + "\n\nNext Steps:\n- Follow up on agreed action items.";
        return executeCopilot(advisorId, SUMMARY_SYSTEM_PROMPT, prompt, fallback);
    }

    private CopilotResult executeCopilot(UUID advisorId, String systemPrompt, String prompt, String fallbackText) {
        List<StudentAiChatSupport.AiConfigModel> configs = chatSupport.getActiveChatConfigs();
        long start = System.currentTimeMillis();
        GeminiGateway.Reply reply = GeminiGateway.Reply.unavailable();
        String usedConfigId = configs.isEmpty() ? null : configs.get(0).id();

        if (configs.isEmpty()) {
            reply = gemini.generate(new GeminiGateway.Request(
                    "GEMINI", "gemini-2.5-flash", null, systemPrompt, List.of(), prompt, 0.3, 1024, 30));
        } else {
            for (StudentAiChatSupport.AiConfigModel cfg : configs) {
                reply = gemini.generate(new GeminiGateway.Request(
                        cfg.provider(), cfg.modelName(), secrets.findRawKey(cfg.id()),
                        systemPrompt, List.of(), prompt, cfg.temperature(), cfg.maxTokens(), cfg.timeoutSeconds()));
                if (!reply.fallback()) {
                    usedConfigId = cfg.id();
                    break;
                }
            }
        }
        int latencyMs = (int) (System.currentTimeMillis() - start);
        logUsage(advisorId, usedConfigId, reply, latencyMs);
        return new CopilotResult(reply.fallback() ? fallbackText : reply.text(), reply.fallback());
    }

    private StudentSnapshot loadStudentSnapshot(UUID studentId) {
        List<StudentSnapshot> rows = jdbc.query("""
                SELECT u.full_name, s.student_code, s.current_gpa, s.total_credits_earned, s.academic_status
                FROM users u JOIN students s ON s.user_id = u.id WHERE u.id = ?
                """,
                (rs, row) -> new StudentSnapshot(
                        rs.getString("full_name"), rs.getString("student_code"),
                        rs.getDouble("current_gpa"), rs.getInt("total_credits_earned"),
                        rs.getString("academic_status"), failedCourses(studentId), openWarnings(studentId)),
                studentId.toString());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found");
        }
        return rows.get(0);
    }

    private List<String> failedCourses(UUID studentId) {
        return jdbc.query("""
                SELECT c.code FROM student_course_records r JOIN courses c ON c.id = r.course_id
                WHERE r.student_id = ? AND r.status = 'FAILED' LIMIT 10
                """, (rs, row) -> rs.getString(1), studentId.toString());
    }

    private List<String> openWarnings(UUID studentId) {
        return jdbc.query("""
                SELECT CONCAT(warning_type, ' (', severity, ')') FROM academic_warnings
                WHERE student_id = ? AND resolved_at IS NULL LIMIT 5
                """, (rs, row) -> rs.getString(1), studentId.toString());
    }

    private String loadSessionAgenda(UUID advisorId, UUID sessionId) {
        List<String> rows = jdbc.query(
                "SELECT agenda FROM advising_sessions WHERE id = ? AND advisor_id = ?",
                (rs, row) -> rs.getString(1), sessionId.toString(), advisorId.toString());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Advising session not found");
        }
        return rows.get(0);
    }

    private void logUsage(UUID userId, String configId, GeminiGateway.Reply reply, int latencyMs) {
        jdbc.update("""
                INSERT INTO ai_usage_logs
                (id, user_id, ai_configuration_id, feature, prompt_tokens, completion_tokens, total_tokens, latency_ms, status)
                VALUES (?, ?, ?, 'SUMMARIZATION', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), userId.toString(), configId,
                reply.promptTokens(), reply.completionTokens(), reply.promptTokens() + reply.completionTokens(),
                latencyMs, reply.fallback() ? "FALLBACK" : "SUCCESS");
    }

    private static String buildBriefPrompt(StudentSnapshot s) {
        return String.format(
                "Student: %s (%s)%nGPA: %.2f | Credits Earned: %d | Standing: %s%nFailed Courses: %s%nOpen Warnings: %s",
                s.fullName(), s.studentCode(), s.gpa(), s.creditsEarned(), s.academicStatus(),
                s.failedCourses().isEmpty() ? "None" : String.join(", ", s.failedCourses()),
                s.openWarnings().isEmpty() ? "None" : String.join(", ", s.openWarnings()));
    }

    private static String fallbackBrief(StudentSnapshot s) {
        return "Risk & Performance Summary:\n"
                + "- Student " + s.fullName() + " (" + s.studentCode() + "), GPA "
                + String.format("%.2f", s.gpa()) + ", status " + s.academicStatus() + ".\n"
                + "- Failed courses: " + (s.failedCourses().isEmpty() ? "None" : String.join(", ", s.failedCourses())) + "\n"
                + "- Open warnings: " + (s.openWarnings().isEmpty() ? "None" : String.join(", ", s.openWarnings())) + "\n"
                + "\nSuggested Next Steps:\n"
                + "- Review study load, attendance habits, and retake priorities for the upcoming semester.";
    }

    private record StudentSnapshot(String fullName, String studentCode, double gpa, int creditsEarned,
            String academicStatus, List<String> failedCourses, List<String> openWarnings) {}

    public record CopilotResult(String content, boolean fallback) {}
}
