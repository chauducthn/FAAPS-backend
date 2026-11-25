package vn.edu.fpt.faaps.ai.service;

import java.util.List;
import java.util.Map;
import vn.edu.fpt.faaps.ai.dto.AdminAiConfigDtos.AiPresetDto;

final class AdminAiConfigPresets {

    private AdminAiConfigPresets() {
    }

    static List<AiPresetDto> defaultPresets() {
        return List.of(
                new AiPresetDto(
                        "gemini-flash-chat",
                        "Gemini 2.5 Flash - Student Advisor Chat",
                        "GEMINI",
                        "gemini-2.5-flash",
                        "CHAT",
                        "Fast, responsive conversational model for academic guidance and policy questions.",
                        Map.of(
                                "temperature", 0.7,
                                "maxTokens", 2048,
                                "retrievalTopK", 5,
                                "similarityThreshold", 0.75,
                                "timeoutSeconds", 30,
                                "dailyRequestLimit", 100,
                                "systemPrompt",
                                "You are an AI academic advisor for FAAPS. Provide clear, empathetic, and accurate "
                                        + "advice grounded strictly in university regulations.",
                                "fallbackMessage",
                                "The advising assistant is currently unavailable. Please try again shortly or "
                                        + "contact your advisor.")),
                new AiPresetDto(
                        "gemini-pro-roadmap",
                        "Gemini 2.5 Pro - Academic Guidance",
                        "GEMINI",
                        "gemini-2.5-pro",
                        "CHAT",
                        "High reasoning model for prerequisite DAG resolution and semester curriculum roadmaps.",
                        Map.of(
                                "temperature", 0.2,
                                "maxTokens", 4096,
                                "retrievalTopK", 10,
                                "similarityThreshold", 0.80,
                                "timeoutSeconds", 60,
                                "dailyRequestLimit", 30,
                                "systemPrompt",
                                "You are an expert curriculum roadmap planner. Given student transcripts and "
                                        + "curriculum DAG, output an optimal study roadmap.",
                                "fallbackMessage",
                                "Roadmap generation is currently unavailable. Please consult your academic advisor.")),
                new AiPresetDto(
                        "text-embedding-004",
                        "Text Embedding 004 - RAG Document Retrieval",
                        "GEMINI",
                        "text-embedding-004",
                        "EMBEDDING",
                        "Vector embedding model for semantic search over student handbooks and university regulations.",
                        Map.of(
                                "temperature", 0.0,
                                "maxTokens", 1024,
                                "retrievalTopK", 5,
                                "similarityThreshold", 0.70,
                                "timeoutSeconds", 15,
                                "dailyRequestLimit", 500,
                                "systemPrompt", "",
                                "fallbackMessage", "")));
    }
}
