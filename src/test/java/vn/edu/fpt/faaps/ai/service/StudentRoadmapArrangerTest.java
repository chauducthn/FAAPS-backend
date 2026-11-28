package vn.edu.fpt.faaps.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapCourseDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapGoalCheckDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerCourse;

/** The arranger keeps the course set fixed and only moves courses between terms. */
class StudentRoadmapArrangerTest {
    private static RoadmapCourseDto dto(String id, String code, int credits) {
        return new RoadmapCourseDto(id, code, code + " name", credits, null, true, "MEDIUM", "PLANNED",
                "base reason", List.of(), false, null);
    }

    private static PlannerCourse course(String id, String code, int credits, List<List<String>> prereqs) {
        return new PlannerCourse(id, code, code, credits, null, true, 1, false, null, prereqs, List.of());
    }

    private static StudyRoadmapDto base(List<RoadmapSemesterDto> semesters) {
        RoadmapGoalCheckDto check = new RoadmapGoalCheckDto("SP2027", "FA2027", "SU2027", true, 9, 9, 7.0, 8.0,
                8.2, 0);
        return new StudyRoadmapDto("b", "s", "t", "sum", "now", "RULE_BASED", null, semesters.size(), 9, semesters,
                check, List.of(), List.of());
    }

    private static RoadmapSemesterDto term(int n, String code, RoadmapCourseDto... courses) {
        return new RoadmapSemesterDto(n, code, code, 0, "PLANNED", List.of(courses), "", "method " + code);
    }

    private final List<PlannerCourse> open = List.of(
            course("a", "PRF192", 3, List.of()),
            course("b", "PRO192", 3, List.of(List.of("a"))),
            course("c", "MAE101", 3, List.of()));
    private final StudyRoadmapDto system = base(List.of(
            term(1, "SP2027", dto("a", "PRF192", 3), dto("c", "MAE101", 3)),
            term(2, "SU2027", dto("b", "PRO192", 3))));

    @Test
    void repairMovesACourseAfterItsPrerequisite() {
        var result = StudentRoadmapArranger.arrange(system, Map.of("b", "SP2027"), Map.of("b", "ai reason"),
                Map.of(), open, Set.of(), 21, StudentRoadmapArranger.Mode.REPAIR);

        assertEquals("SU2027", termOf(result.semesters(), "PRO192"));
        assertTrue(result.notes().stream().anyMatch(n -> n.startsWith("Moved PRO192 from SP2027 to SU2027")));
        assertEquals("ai reason", result.semesters().get(1).courses().get(0).reason());
    }

    @Test
    void repairRespectsTheCreditLimit() {
        var result = StudentRoadmapArranger.arrange(system, Map.of("b", "SP2027"), Map.of(), Map.of(), open,
                Set.of("a"), 6, StudentRoadmapArranger.Mode.REPAIR);

        assertTrue(result.semesters().stream().allMatch(s -> s.totalCredits() <= 6));
        assertEquals(9, result.semesters().stream().mapToInt(RoadmapSemesterDto::totalCredits).sum());
    }

    @Test
    void repairCanMoveACourseEarlierWhenItIsValid() {
        var result = StudentRoadmapArranger.arrange(system, Map.of("c", "SU2027"), Map.of(), Map.of(), open,
                Set.of(), 21, StudentRoadmapArranger.Mode.REPAIR);

        assertEquals("SU2027", termOf(result.semesters(), "MAE101"));
        assertTrue(result.notes().isEmpty());
    }

    @Test
    void keepLeavesTheStudentPlacementAndWarns() {
        var result = StudentRoadmapArranger.arrange(system, Map.of("b", "SP2027"), Map.of(), Map.of(), open,
                Set.of(), 6, StudentRoadmapArranger.Mode.KEEP);

        assertEquals("SP2027", termOf(result.semesters(), "PRO192"));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("comes before its prerequisite PRF192")));
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("over the limit of 6")));
        assertEquals("SP2027", result.goalCheck().projectedGraduationTerm());
    }

    @Test
    void aGapTermKeepsRealTermCodes() {
        var result = StudentRoadmapArranger.arrange(system, Map.of("b", "SP2028"), Map.of(), Map.of(), open,
                Set.of(), 21, StudentRoadmapArranger.Mode.KEEP);

        assertEquals(List.of("SP2027", "SP2028"), result.semesters().stream().map(RoadmapSemesterDto::semesterCode).toList());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("later than your target FA2027")));
    }

    private static String termOf(List<RoadmapSemesterDto> semesters, String code) {
        return semesters.stream().filter(s -> s.courses().stream().anyMatch(c -> c.courseCode().equals(code)))
                .map(RoadmapSemesterDto::semesterCode).findFirst().orElse(null);
    }
}
