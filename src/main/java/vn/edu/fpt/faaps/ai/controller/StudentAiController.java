package vn.edu.fpt.faaps.ai.controller;

import vn.edu.fpt.faaps.ai.service.StudentAiService;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.auth.model.AuthSession;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiChatMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.AiMessageFeedbackDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.CreateAiConversationDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapVersionsDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SaveCustomRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SelectRoadmapVersionDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SendAiMessageResultDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

@RestController
@RequestMapping({"/api/student/ai", "/student/ai"})
public class StudentAiController {

    private final StudentAiService studentAiService;

    public StudentAiController(StudentAiService studentAiService) {
        this.studentAiService = studentAiService;
    }

    @GetMapping("/conversations")
    public List<AiConversationDto> listConversations(@AuthenticationPrincipal AuthSession student) {
        return studentAiService.listConversations(student.id());
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public AiConversationDto createConversation(
            @AuthenticationPrincipal AuthSession student,
            @RequestBody(required = false) CreateAiConversationDto dto) {
        return studentAiService.createConversation(student.id(), dto != null ? dto : new CreateAiConversationDto(null));
    }

    @GetMapping("/conversations/{id}/messages")
    public List<AiChatMessageDto> getMessages(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthSession student) {
        return studentAiService.getMessages(id, student.id());
    }

    @PostMapping("/conversations/{id}/messages")
    public SendAiMessageResultDto sendMessage(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody SendAiMessageDto body) {
        return studentAiService.sendMessage(id, student.id(), body);
    }

    @PostMapping(value = "/conversations/{id}/messages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SendAiMessageResultDto sendMessageWithAttachment(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthSession student,
            @RequestParam(value = "content", required = false, defaultValue = "") String content,
            @RequestParam("file") MultipartFile file) {
        return studentAiService.sendMessageWithAttachment(id, student.id(), content, file);
    }

    @PatchMapping("/messages/{id}/feedback")
    public Map<String, Boolean> sendFeedback(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody AiMessageFeedbackDto feedbackDto) {
        studentAiService.sendFeedback(id, student.id(), feedbackDto);
        return Map.of("updated", true);
    }

    @GetMapping("/roadmap")
    public StudyRoadmapDto getRoadmap(@AuthenticationPrincipal AuthSession student) {
        return studentAiService.getRoadmap(student.id());
    }

    @PostMapping("/roadmap")
    public StudyRoadmapDto generateRoadmap(
            @AuthenticationPrincipal AuthSession student,
            @RequestBody(required = false) GenerateRoadmapDto dto) {
        return studentAiService.generateRoadmap(student.id(), dto != null ? dto : new GenerateRoadmapDto(null, null));
    }

    /** The SYSTEM, AI and CUSTOM roadmap versions, and the one the student follows. */
    @GetMapping("/roadmap/versions")
    public RoadmapVersionsDto getRoadmapVersions(@AuthenticationPrincipal AuthSession student) {
        return studentAiService.roadmapVersions(student.id());
    }

    /** Rebuilds the system plan for the given goals, then asks the AI to improve it. */
    @PostMapping("/roadmap/ai")
    public StudyRoadmapDto generateAiRoadmap(
            @AuthenticationPrincipal AuthSession student,
            @RequestBody(required = false) GenerateRoadmapDto dto) {
        return studentAiService.generateAiRoadmap(student.id(), dto != null ? dto : new GenerateRoadmapDto(null, null));
    }

    @PutMapping("/roadmap/custom")
    public StudyRoadmapDto saveCustomRoadmap(
            @AuthenticationPrincipal AuthSession student,
            @RequestBody SaveCustomRoadmapDto dto) {
        return studentAiService.saveCustomRoadmap(student.id(), dto);
    }

    @PutMapping("/roadmap/active")
    public RoadmapVersionsDto selectRoadmapVersion(
            @AuthenticationPrincipal AuthSession student,
            @RequestBody SelectRoadmapVersionDto dto) {
        return studentAiService.selectRoadmapVersion(student.id(), dto == null ? null : dto.kind());
    }
}


