package vn.edu.fpt.faaps.ai.service;

import java.util.List;
import java.util.Map;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPresetDto;

final class AdminAiConfigPresets {

    private static final String FPTU_ADVISOR_PROMPT =
            "You are FAAPS, the official AI Academic Advisor for FPT University (FPTU). "
            + "You have live access to each student's curriculum (FLM), transcript, GPA, prerequisites, "
            + "academic warnings, and FPTU policies, as well as the full conversation history. "
            + "Answer in the student's language (Vietnamese or English), give concrete semester study plans, "
            + "check prerequisites and OJT/Capstone milestones, and recommend contacting their human advisor when approval is needed.";

    private AdminAiConfigPresets() {
    }

    static List<AiPresetDto> defaultPresets() {
        return List.of(
                new AiPresetDto(
                        "gemini-2.5-flash-chat",
                        "Google AI Studio · Gemini 2.5 Flash (Advisor Chat)",
                        "GEMINI",
                        "gemini-2.5-flash",
                        "CHAT",
                        "Fast, low-latency Google AI Studio model for student Q&A, curriculum lookup, and study planning.",
                        Map.of(
                                "temperature", 0.5,
                                "maxTokens", 4096,
                                "retrievalTopK", 12,
                                "similarityThreshold", 0.72,
                                "timeoutSeconds", 30,
                                "dailyRequestLimit", 2000,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "The advising assistant is temporarily busy. Please try again shortly or contact your academic advisor.")),
                new AiPresetDto(
                        "gemini-2.5-pro-planning",
                        "Google AI Studio · Gemini 2.5 Pro (Deep Planning)",
                        "GEMINI",
                        "gemini-2.5-pro",
                        "CHAT",
                        "High-reasoning Google AI Studio model for complex study roadmaps, retake planning, and advisor briefs.",
                        Map.of(
                                "temperature", 0.3,
                                "maxTokens", 8192,
                                "retrievalTopK", 16,
                                "similarityThreshold", 0.78,
                                "timeoutSeconds", 60,
                                "dailyRequestLimit", 500,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "Academic planning is temporarily unavailable. Please consult your advisor.")),
                new AiPresetDto(
                        "openrouter-gemini-flash",
                        "OpenRouter · Google Gemini 2.5 Flash",
                        "OPENROUTER",
                        "google/gemini-2.5-flash",
                        "CHAT",
                        "Route Gemini 2.5 Flash through OpenRouter with unified billing and automatic fallback.",
                        Map.of(
                                "temperature", 0.5,
                                "maxTokens", 4096,
                                "retrievalTopK", 12,
                                "similarityThreshold", 0.72,
                                "timeoutSeconds", 35,
                                "dailyRequestLimit", 2000,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "The advising assistant is temporarily busy. Please try again shortly or contact your academic advisor.")),
                new AiPresetDto(
                        "openrouter-gpt-4o-mini",
                        "OpenRouter · OpenAI GPT-4o Mini",
                        "OPENROUTER",
                        "openai/gpt-4o-mini",
                        "CHAT",
                        "Cost-effective OpenAI model via OpenRouter for fast Vietnamese & English academic advising.",
                        Map.of(
                                "temperature", 0.4,
                                "maxTokens", 4096,
                                "retrievalTopK", 12,
                                "similarityThreshold", 0.75,
                                "timeoutSeconds", 35,
                                "dailyRequestLimit", 2000,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "The advising assistant is temporarily busy. Please try again shortly or contact your academic advisor.")),
                new AiPresetDto(
                        "openrouter-claude-sonnet",
                        "OpenRouter · Claude 3.5 Sonnet (Advisor Copilot)",
                        "OPENROUTER",
                        "anthropic/claude-3.5-sonnet",
                        "CHAT",
                        "Strong reasoning & Vietnamese fluency via OpenRouter for advisor briefs and complex study plans.",
                        Map.of(
                                "temperature", 0.3,
                                "maxTokens", 4096,
                                "retrievalTopK", 14,
                                "similarityThreshold", 0.78,
                                "timeoutSeconds", 45,
                                "dailyRequestLimit", 1000,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "The advising assistant is temporarily busy. Please try again shortly or contact your academic advisor.")),
                new AiPresetDto(
                        "openrouter-llama-free",
                        "OpenRouter · Llama 3.3 70B (Free Tier)",
                        "OPENROUTER",
                        "meta-llama/llama-3.3-70b-instruct:free",
                        "CHAT",
                        "Zero-cost OpenRouter fallback model when primary quotas are exhausted.",
                        Map.of(
                                "temperature", 0.5,
                                "maxTokens", 2048,
                                "retrievalTopK", 10,
                                "similarityThreshold", 0.70,
                                "timeoutSeconds", 45,
                                "dailyRequestLimit", 1000,
                                "systemPrompt", FPTU_ADVISOR_PROMPT,
                                "fallbackMessage",
                                "The advising assistant is temporarily busy. Please try again shortly or contact your academic advisor.")));
    }
}
