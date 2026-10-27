package vn.edu.fpt.faaps.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public class StudentAiDtos {

    public record AiConversationDto(
            String id,
            String userId,
            String title,
            String status,
            int messageCount,
            String lastMessageAt,
            String createdAt
    ) {}

    public record CreateAiConversationDto(
            String title
    ) {}

    public record AiChatSourceDto(
            String chunkId,
            String documentTitle,
            String sectionTitle,
            String quotedText,
            Double relevanceScore,
            Integer rankPosition
    ) {}

    public record AiChatMessageDto(
            String id,
            String conversationId,
            String sender,
            String content,
            String questionCategory,
            boolean isUnsupported,
            boolean isFallback,
            Double confidenceScore,
            String aiConfigurationId,
            List<AiChatSourceDto> sources,
            Boolean wasHelpful,
            String feedback,
            String createdAt
    ) {}

    public record SendAiMessageDto(
            @NotBlank String content
    ) {}

    public record SendAiMessageResultDto(
            AiChatMessageDto userMessage,
            AiChatMessageDto assistantMessage
    ) {}

    public record AiMessageFeedbackDto(
            boolean wasHelpful,
            String feedback
    ) {}

    public record GenerateRoadmapDto(
            String goal,
            Integer maxCreditsPerSemester
    ) {}

    public record RoadmapCourseDto(
            String courseId,
            String courseCode,
            String courseName,
            int credits,
            String categoryName,
            boolean isMandatory,
            String priority,
            String status,
            String reason,
            List<String> prerequisites
    ) {}

    public record RoadmapSemesterDto(
            int semesterNumber,
            String semesterCode,
            String title,
            int totalCredits,
            String status,
            List<RoadmapCourseDto> courses,
            String note
    ) {}

    public record StudyRoadmapDto(
            String id,
            String studentId,
            String title,
            String summary,
            String generatedAt,
            String modelName,
            Double confidenceScore,
            int totalSemesters,
            int totalCredits,
            List<RoadmapSemesterDto> semesters
    ) {}
}

