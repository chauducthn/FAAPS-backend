package vn.edu.fpt.faaps.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.RoadmapSemesterDto;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerCourse;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerInput;
import vn.edu.fpt.faaps.ai.service.StudentRoadmapPlanner.PlannerResult;

class StudentRoadmapPlannerTest {

    private static PlannerCourse course(String id, int semester, int credits, List<List<String>> prereqs) {
        return new PlannerCourse(id, id, id + " name", credits, null, true, semester, false, null, prereqs,
                prereqs.stream().flatMap(List::stream).toList());
    }

    @Test
    void termCodesFollowSpringSummerFall() {
        assertEquals("SU2026", StudentRoadmapPlanner.nextTerm("SP26"));
        assertEquals("SP2027", StudentRoadmapPlanner.nextTerm("FA2026"));
        assertEquals(4, StudentRoadmapPlanner.termDistance("SP2027", "SP2028"));
        assertEquals("SP2027", StudentRoadmapService.startTerm("FA2026", LocalDate.of(2026, 11, 28)));
        assertEquals("FA2026", StudentRoadmapService.startTerm(null, LocalDate.of(2026, 9, 10)));
    }

    @Test
    void schedulesPrerequisitesBeforeDependentsAndRetakesFirst() {
        PlannerCourse prf = course("PRF192", 1, 3, List.of());
        PlannerCourse pro = course("PRO192", 2, 3, List.of(List.of("PRF192")));
        PlannerCourse failedMath = new PlannerCourse("MAE101", "MAE101", "Maths", 3, null, true, 1, true, 3.5,
                List.of(), List.of());
        PlannerResult result = StudentRoadmapPlanner.plan(new PlannerInput(List.of(pro, prf, failedMath), Set.of(),
                "SP2027", null, 20, "JOB", 6.0, 7.0, 30));

        List<RoadmapSemesterDto> terms = result.semesters();
        assertEquals(2, terms.size());
        assertEquals("SP2027", terms.get(0).semesterCode());
        assertEquals("MAE101", terms.get(0).courses().get(0).courseCode());
        assertTrue(terms.get(0).courses().get(0).retake());
        assertTrue(terms.get(0).courses().stream().noneMatch(c -> c.courseCode().equals("PRO192")));
        assertEquals("PRO192", terms.get(1).courses().get(0).courseCode());
        assertEquals("SU2027", terms.get(1).semesterCode());
    }

    @Test
    void alternativePrerequisitesInOneGroupAreEnough() {
        PlannerCourse adv = course("ADV", 2, 3, List.of(List.of("A", "B")));
        PlannerResult result = StudentRoadmapPlanner.plan(new PlannerInput(List.of(adv, course("A", 1, 3, List.of())),
                Set.of("B"), "SP2027", null, 20, null, null, null, 0));
        assertEquals(1, result.semesters().size());
    }

    @Test
    void spreadsCreditsUntilTargetAndFlagsLatePlans() {
        List<PlannerCourse> courses = List.of(course("C1", 1, 5, List.of()), course("C2", 1, 5, List.of()),
                course("C3", 1, 5, List.of()), course("C4", 1, 5, List.of()));
        PlannerResult onTime = StudentRoadmapPlanner.plan(new PlannerInput(courses, Set.of(), "SP2027", "SU2027",
                20, null, null, null, 0));
        assertEquals(2, onTime.semesters().size());
        assertEquals(Boolean.TRUE, onTime.goalCheck().onTrack());

        PlannerResult late = StudentRoadmapPlanner.plan(new PlannerInput(courses, Set.of(), "SP2027", "SP2027",
                10, null, null, null, 0));
        assertEquals(Boolean.FALSE, late.goalCheck().onTrack());
        assertFalse(late.warnings().isEmpty());
    }

    @Test
    void ojtGetsItsOwnTermAndUnreachableGpaIsReported() {
        PlannerCourse ojt = new PlannerCourse("OJT202", "OJT202", "On the job training", 10, "OJT", true, 1, false,
                null, List.of(), List.of());
        PlannerResult result = StudentRoadmapPlanner.plan(new PlannerInput(
                List.of(ojt, course("SWP391", 1, 3, List.of())), Set.of(), "SP2027", null, 20, null, 5.0, 9.9, 100));
        assertTrue(result.semesters().stream().anyMatch(t -> t.courses().size() == 1
                && t.courses().get(0).courseCode().equals("OJT202")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("out of reach")));
    }
}
