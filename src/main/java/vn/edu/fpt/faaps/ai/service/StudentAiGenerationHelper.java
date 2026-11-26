package vn.edu.fpt.faaps.ai.service;

import java.util.List;

/** Runs AI model generation across configured providers with fallback resolution. */
final class StudentAiGenerationHelper {

    private static final String DEFAULT_SYSTEM_PROMPT = StudentAiChatSupport.DEFAULT_ADVISOR_PROMPT;

    private final GeminiGateway gemini;
    private final AiSecretStore secretStore;

    StudentAiGenerationHelper(GeminiGateway gemini, AiSecretStore secretStore) {
        this.gemini = gemini;
        this.secretStore = secretStore;
    }

    GenerationOutcome generateWithFallback(List<StudentAiChatSupport.AiConfigModel> configs,
            String academicContext, List<GeminiGateway.Turn> history, String userContent) {
        if (configs.isEmpty()) {
            GeminiGateway.Reply reply = gemini.generate(new GeminiGateway.Request(
                    "GEMINI", null, null, DEFAULT_SYSTEM_PROMPT + academicContext, history, userContent, 0.5, 4096, 30));
            return new GenerationOutcome(reply, null);
        }
        GeminiGateway.Reply lastReply = GeminiGateway.Reply.unavailable();
        for (StudentAiChatSupport.AiConfigModel cfg : configs) {
            String prompt = (cfg.systemPrompt() == null || cfg.systemPrompt().isBlank()
                    ? DEFAULT_SYSTEM_PROMPT : cfg.systemPrompt()) + academicContext;
            GeminiGateway.Reply reply = gemini.generate(new GeminiGateway.Request(
                    cfg.provider(), cfg.modelName(), secretStore.findRawKey(cfg.id()),
                    prompt, history, userContent, cfg.temperature(), cfg.maxTokens(), cfg.timeoutSeconds()));
            if (!reply.fallback()) {
                return new GenerationOutcome(reply, cfg);
            }
            lastReply = reply;
        }
        return new GenerationOutcome(lastReply, configs.get(0));
    }

    String resolveAnswerContent(GeminiGateway.Reply reply, StudentAiChatSupport.AiConfigModel activeConfig) {
        if (!reply.fallback()) {
            return reply.text();
        }
        if (activeConfig != null && activeConfig.fallbackMessage() != null && !activeConfig.fallbackMessage().isBlank()) {
            return activeConfig.fallbackMessage();
        }
        return "The academic assistant is unavailable. Please contact your advisor.";
    }

    record GenerationOutcome(GeminiGateway.Reply reply, StudentAiChatSupport.AiConfigModel config) {}
}
