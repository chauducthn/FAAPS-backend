package vn.edu.fpt.faaps.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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
            @JsonAlias("maxCredits") Integer maxCreditsPerSemester,
            String expectedGraduationSemesterId,
            String orientation,
            Double targetGpa
    ) {
        public GenerateRoadmapDto(String goal, Integer maxCreditsPerSemester) {
            this(goal, maxCreditsPerSemester, null, null, null);
        }
    }

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
            List<String> prerequisites,
            /** True when the student failed this course before and has to retake it. */
            boolean retake,
            /** How to study this course, picked from its kind (coding, maths, language, OJT, capstone...). */
            String studyTip
    ) {
        public RoadmapCourseDto(String courseId, String courseCode, String courseName, int credits,
                String categoryName, boolean isMandatory, String priority, String status, String reason,
                List<String> prerequisites) {
            this(courseId, courseCode, courseName, credits, categoryName, isMandatory, priority, status, reason,
                    prerequisites, false, null);
        }
    }

    public record RoadmapSemesterDto(
            int semesterNumber,
            String semesterCode,
            String title,
            int totalCredits,
            String status,
            List<RoadmapCourseDto> courses,
            String note,
            /** The study method suggested for this term as a whole. */
            String studyMethod
    ) {
        public RoadmapSemesterDto(int semesterNumber, String semesterCode, String title, int totalCredits,
                String status, List<RoadmapCourseDto> courses, String note) {
            this(semesterNumber, semesterCode, title, totalCredits, status, courses, note, null);
        }
    }

    /** How the plan compares with the goals the student set in Study Journey. */
    public record RoadmapGoalCheckDto(
            /** First term the plan schedules, e.g. SP2027. */
            String startTerm,
            /** The graduation term the student is aiming for, null when none was set. */
            String targetGraduationTerm,
            /** The term the last planned course falls in. */
            String projectedGraduationTerm,
            /** True when the projected term is not later than the target term. */
            Boolean onTrack,
            int remainingCredits,
            int recommendedCreditsPerTerm,
            Double currentGpa,
            Double targetGpa,
            /** Average score needed on the remaining credits to reach the target GPA; null without a target. */
            Double requiredAverageScore,
            int retakeCount
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
            List<RoadmapSemesterDto> semesters,
            RoadmapGoalCheckDto goalCheck,
            /** Things the student should know: deadline risk, unreachable GPA, courses still in progress... */
            List<String> warnings,
            /** General study methods for this student, from their orientation, target GPA and history. */
            List<String> studyMethods
    ) {
        public StudyRoadmapDto(String id, String studentId, String title, String summary, String generatedAt,
                String modelName, Double confidenceScore, int totalSemesters, int totalCredits,
                List<RoadmapSemesterDto> semesters) {
            this(id, studentId, title, summary, generatedAt, modelName, confidenceScore, totalSemesters,
                    totalCredits, semesters, null, List.of(), List.of());
        }
    }

    /**
     * The roadmap versions of a student: the system plan, the AI refinement of it and the student's own edit.
     * {@code activeKind} is the version the student chose to follow (SYSTEM, AI or CUSTOM).
     */
    public record RoadmapVersionsDto(
            String activeKind,
            StudyRoadmapDto system,
            StudyRoadmapDto ai,
            StudyRoadmapDto custom,
            /** The version the custom plan was edited from (SYSTEM or AI). */
            String customBasedOn
    ) {}

    /** One term of a custom plan: which courses the student puts in it. */
    public record CustomRoadmapTermDto(String semesterCode, List<String> courseIds) {}

    /** The student's own arrangement, started from the SYSTEM or the AI version. */
    public record SaveCustomRoadmapDto(String basedOn, List<CustomRoadmapTermDto> terms) {}

    public record SelectRoadmapVersionDto(String kind) {}
}
