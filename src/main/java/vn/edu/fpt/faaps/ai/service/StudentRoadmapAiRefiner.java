package vn.edu.fpt.faaps.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

/**
 * Asks the configured AI model to improve the system roadmap for the student's goals.
 *
 * <p>The model only <em>proposes</em> a term for each course of the system plan, with a reason, a study method per
 * term and general advice. It cannot add or remove courses, and its proposal is checked and repaired by
 * {@link StudentRoadmapArranger} before anyone sees it, so a wrong answer can never produce an invalid plan.
 */
final class StudentRoadmapAiRefiner {
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            You are an academic planner at FPT University (Vietnam). You improve a student's term-by-term study \
            plan so it fits their goals. You reply with a single JSON object and nothing else.""";

    private final GeminiGateway gemini;
    private final AiSecretStore secrets;
    private final StudentAiChatSupport configs;

    StudentRoadmapAiRefiner(GeminiGateway gemini, AiSecretStore secrets, StudentAiChatSupport configs) {
        this.gemini = gemini;
        this.secrets = secrets;
        this.configs = configs;
    }

    /** What the student is aiming for. Every field may be null. */
    record Goals(String orientation, String targetRole, Double targetGpa, Double currentGpa,
            String targetGraduationTerm) {}

    /** The model's proposal, keyed by course id, plus what was ignored from it. */
    record Proposal(Map<String, String> termByCourseId, Map<String, String> reasonByCourseId,
            Map<String, String> methodByTerm, String summary, List<String> advice, String modelName,
            List<String> ignored) {}

    Proposal propose(StudyRoadmapDto system, Goals goals, Map<String, String> workloadByCourseId, int maxCredits,
            List<String> allowedTerms) {
        String prompt = prompt(system, goals, workloadByCourseId, maxCredits, allowedTerms);
        Reply reply = call(prompt);
        if (reply.text() == null || reply.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI planner is unavailable right now. Try again in a moment.");
        }
        try {
            return parse(reply.text(), system, allowedTerms, reply.model());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI planner returned an answer that could not be read. Try again.");
        }
    }

    private record Reply(String text, String model) {}

    private Reply call(String prompt) {
        List<StudentAiChatSupport.AiConfigModel> active = configs.getActiveChatConfigs();
        if (active.isEmpty()) {
            GeminiGateway.Reply reply = gemini.generate(new GeminiGateway.Request(
                    "GEMINI", DEFAULT_MODEL, null, SYSTEM_PROMPT, List.of(), prompt, 0.3, 8192, 60));
            return new Reply(reply.fallback() ? null : reply.text(), DEFAULT_MODEL);
        }
        for (StudentAiChatSupport.AiConfigModel cfg : active) {
            // A roadmap is long JSON: never cut it at a chat-sized token limit.
            int maxTokens = Math.max(cfg.maxTokens() == null ? 0 : cfg.maxTokens(), 8192);
            int timeout = Math.max(cfg.timeoutSeconds() == null ? 0 : cfg.timeoutSeconds(), 60);
            GeminiGateway.Reply reply = gemini.generate(new GeminiGateway.Request(
                    cfg.provider(), cfg.modelName(), secrets.findRawKey(cfg.id()), SYSTEM_PROMPT, List.of(), prompt,
                    0.3, maxTokens, timeout));
            if (!reply.fallback() && reply.text() != null && !reply.text().isBlank()) {
                return new Reply(reply.text(), cfg.modelName());
            }
        }
        return new Reply(null, null);
    }

    static String prompt(StudyRoadmapDto system, Goals goals, Map<String, String> workloadByCourseId,
            int maxCredits, List<String> allowedTerms) {
        StringBuilder sb = new StringBuilder();
        sb.append("Student goals:\n");
        sb.append("- Career orientation: ").append(orNone(goals.orientation())).append('\n');
        sb.append("- Target job role: ").append(orNone(goals.targetRole())).append('\n');
        sb.append("- Target GPA (10-point scale): ").append(goals.targetGpa() == null ? "none" : goals.targetGpa())
                .append(", current GPA: ").append(goals.currentGpa() == null ? "unknown" : goals.currentGpa()).append('\n');
        sb.append("- Target graduation term: ").append(orNone(goals.targetGraduationTerm())).append('\n');
        sb.append("- Credit limit per term: ").append(maxCredits).append("\n\n");
        sb.append("Terms you may use, in order: ").append(String.join(", ", allowedTerms)).append("\n\n");
        sb.append("Current plan (code | name | credits | workload | mandatory/elective | retake | prerequisites):\n");
        for (RoadmapSemesterDto semester : system.semesters()) {
            sb.append(semester.semesterCode()).append(":\n");
            for (RoadmapCourseDto c : semester.courses()) {
                sb.append("  - ").append(c.courseCode()).append(" | ").append(c.courseName()).append(" | ")
                        .append(c.credits()).append(" | ")
                        .append(workloadByCourseId.getOrDefault(c.courseId(), "UNKNOWN")).append(" | ")
                        .append(c.isMandatory() ? "mandatory" : "elective").append(" | ")
                        .append(c.retake() ? "retake" : "-").append(" | ")
                        .append(c.prerequisites() == null || c.prerequisites().isEmpty() ? "-"
                                : String.join(", ", c.prerequisites()))
                        .append('\n');
            }
        }
        sb.append("""

                Rules (a plan that breaks them is rejected):
                1. Use every course code of the current plan exactly once. Never add, drop or rename a course.
                2. A course comes in a later term than all its prerequisites.
                3. Stay within the credit limit per term. An OJT course takes a term on its own.
                4. Use only the terms listed above. Finish by the target graduation term when possible.

                Improve the plan for this student: spread HEAVY courses so no term stacks several of them, \
                put retakes early, order the courses that matter for the target job role so each builds on the \
                previous one, and keep terms lighter when the target GPA is high. Keep what is already good.

                Reply with JSON only, in this shape:
                {"summary": "two sentences on what you changed and why",
                 "advice": ["three to five short, concrete study tips for this student"],
                 "terms": [{"term": "SP2027", "method": "one sentence on how to study this term",
                            "courses": [{"code": "PRJ301", "reason": "one sentence on why it is in this term"}]}]}
                """);
        return sb.toString();
    }

    static Proposal parse(String raw, StudyRoadmapDto system, List<String> allowedTerms, String modelName) {
        JsonNode root;
        try {
            root = JSON.readTree(extractJson(raw));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Not JSON", ex);
        }
        Map<String, String> idByCode = new HashMap<>();
        for (RoadmapSemesterDto semester : system.semesters()) {
            for (RoadmapCourseDto c : semester.courses()) {
                idByCode.put(c.courseCode().strip().toUpperCase(Locale.ROOT), c.courseId());
            }
        }
        Set<String> allowed = new LinkedHashSet<>();
        allowedTerms.forEach(t -> allowed.add(StudentRoadmapPlanner.normalizeTerm(t)));

        Map<String, String> terms = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Set<String> ignored = new LinkedHashSet<>();
        for (JsonNode termNode : root.path("terms")) {
            String term = StudentRoadmapPlanner.normalizeTerm(termNode.path("term").asText(null));
            if (term == null || !allowed.contains(term)) {
                ignored.add("term " + termNode.path("term").asText("?"));
                continue;
            }
            String method = termNode.path("method").asText(null);
            if (method != null && !method.isBlank()) {
                methods.put(term, method.strip());
            }
            for (JsonNode courseNode : termNode.path("courses")) {
                String code = courseNode.isTextual() ? courseNode.asText() : courseNode.path("code").asText("");
                String id = idByCode.get(code.strip().toUpperCase(Locale.ROOT));
                if (id == null) {
                    ignored.add(code.isBlank() ? "an unnamed course" : code);
                    continue;
                }
                if (terms.putIfAbsent(id, term) != null) {
                    ignored.add(code + " (listed twice)");
                    continue;
                }
                String reason = courseNode.path("reason").asText(null);
                if (reason != null && !reason.isBlank()) {
                    reasons.put(id, reason.strip());
                }
            }
        }
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("No usable course placement");
        }
        List<String> advice = new ArrayList<>();
        for (JsonNode tip : root.path("advice")) {
            if (tip.isTextual() && !tip.asText().isBlank()) {
                advice.add(tip.asText().strip());
            }
        }
        String summary = root.path("summary").asText(null);
        List<String> ignoredNotes = new ArrayList<>();
        if (!ignored.isEmpty()) {
            ignoredNotes.add("The AI suggestion for " + String.join(", ", ignored)
                    + " was not valid and was ignored; those courses keep the system placement.");
        }
        return new Proposal(terms, reasons, methods, summary == null ? null : summary.strip(), advice, modelName,
                ignoredNotes);
    }

    /** The JSON object inside a reply that may be wrapped in a Markdown fence or a sentence. */
    static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object");
        }
        return raw.substring(start, end + 1);
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value.strip();
    }
}
