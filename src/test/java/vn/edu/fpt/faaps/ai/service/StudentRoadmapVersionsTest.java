package vn.edu.fpt.faaps.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.CustomRoadmapTermDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.SaveCustomRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;

/** SYSTEM, AI and CUSTOM roadmap versions, and which one the student follows. */
class StudentRoadmapVersionsTest {
    private JdbcTemplate jdbc;
    private UUID studentId;
    private String aiReply;
    private StudentAiService service;

    @BeforeEach
    void setUp() {
        StudentAiTestSupport.Fixture fixture = StudentAiTestSupport.createFixture();
        jdbc = fixture.jdbc();
        studentId = fixture.studentId();
        GeminiGateway stub = new GeminiGateway("", new ObjectMapper()) {
            @Override
            Reply generate(Request request) {
                return aiReply == null ? Reply.unavailable() : new Reply(aiReply, false, 10, 10);
            }
        };
        service = new StudentAiService(jdbc, stub);
    }

    @Test
    void theFirstSystemRoadmapIsTheActiveVersion() {
        StudyRoadmapDto system = service.generateRoadmap(studentId, new GenerateRoadmapDto(null, 20));

        var versions = service.roadmapVersions(studentId);
        assertEquals("SYSTEM", versions.activeKind());
        assertEquals(system.id(), versions.system().id());
        assertEquals(system.id(), service.getRoadmap(studentId).id());
    }

    @Test
    void theAiVersionIsRepairedAndKeptBesideTheSystemVersion() {
        service.generateRoadmap(studentId, new GenerateRoadmapDto(null, 20));
        String first = service.roadmapVersions(studentId).system().semesters().get(0).semesterCode();
        // The AI puts PRO192 in the same term as its prerequisite PRF192, and invents a course.
        aiReply = "```json\n{\"summary\":\"Front-loaded the basics.\",\"advice\":[\"Code every day\"],"
                + "\"terms\":[{\"term\":\"" + first + "\",\"method\":\"Focus on labs\",\"courses\":["
                + "{\"code\":\"PRF192\",\"reason\":\"Start with the basics\"},"
                + "{\"code\":\"PRO192\",\"reason\":\"Right after\"},{\"code\":\"XYZ999\"}]}]}\n```";

        StudyRoadmapDto ai = service.generateAiRoadmap(studentId, new GenerateRoadmapDto("Backend developer", 20));

        assertEquals("Front-loaded the basics.", ai.summary());
        assertEquals(List.of("Code every day"), ai.studyMethods());
        assertEquals(6, ai.totalCredits());
        assertTrue(ai.semesters().size() >= 2, "PRO192 must be pushed after PRF192");
        assertEquals("PRF192", ai.semesters().get(0).courses().get(0).courseCode());
        assertTrue(ai.warnings().stream().anyMatch(w -> w.contains("XYZ999")));
        assertTrue(ai.warnings().stream().anyMatch(w -> w.startsWith("Moved PRO192")));

        var versions = service.roadmapVersions(studentId);
        assertEquals("SYSTEM", versions.activeKind(), "Generating the AI version does not switch the plan");
        assertNotNull(versions.system());
        assertNotNull(versions.ai());

        versions = service.selectRoadmapVersion(studentId, "AI");
        assertEquals("AI", versions.activeKind());
        assertEquals("AI Study Roadmap", service.getRoadmap(studentId).title());
    }

    @Test
    void anUnavailableAiLeavesTheSystemVersionAlone() {
        service.generateRoadmap(studentId, new GenerateRoadmapDto(null, 20));
        aiReply = null;

        assertThrows(ResponseStatusException.class,
                () -> service.generateAiRoadmap(studentId, new GenerateRoadmapDto(null, 20)));
        assertEquals("SYSTEM", service.roadmapVersions(studentId).activeKind());
    }

    @Test
    void theCustomVersionKeepsTheStudentChoiceAndBecomesActive() {
        StudyRoadmapDto system = service.generateRoadmap(studentId, new GenerateRoadmapDto(null, 20));
        RoadmapSemesterDto firstTerm = system.semesters().get(0);
        List<String> all = system.semesters().stream().flatMap(t -> t.courses().stream())
                .map(c -> c.courseId()).toList();

        StudyRoadmapDto custom = service.saveCustomRoadmap(studentId, new SaveCustomRoadmapDto("SYSTEM",
                List.of(new CustomRoadmapTermDto(firstTerm.semesterCode(), all))));

        assertEquals(1, custom.semesters().size());
        assertEquals("CUSTOM", custom.modelName());
        assertTrue(custom.warnings().stream().anyMatch(w -> w.contains("comes before its prerequisite PRF192")));
        var versions = service.roadmapVersions(studentId);
        assertEquals("CUSTOM", versions.activeKind());
        assertEquals("SYSTEM", versions.customBasedOn());
        assertEquals("My Study Roadmap", service.getRoadmap(studentId).title());
    }

    @Test
    void customizingAMissingVersionIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.saveCustomRoadmap(studentId, new SaveCustomRoadmapDto("AI", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> service.selectRoadmapVersion(studentId, "OTHER"));
    }
}
