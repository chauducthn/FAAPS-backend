package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.HistoricalStats;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload.WorkloadInput;

class CourseWorkloadEvaluatorTest {

    @Test
    void layer1RuleBasedScoresLightMediumAndHeavyAccurately() {
        CourseWorkload light = CourseWorkloadEvaluator.evaluate(new WorkloadInput(
                "1", "VOV114", "Vovinam 1", 2, "PE", "Physical Education", null,
                "Pass/Fail attendance & practice", null, 0, 0, 0), HistoricalStats.empty());
        assertEquals(CourseWorkload.LEVEL_LIGHT, light.level());
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, light.source());
        assertFalse(light.ruleReasons().isEmpty());

        CourseWorkload medium = CourseWorkloadEvaluator.evaluate(new WorkloadInput(
                "2", "SWE201c", "Introduction to Software Engineering", 3, "CORE", "Core", null,
                "Coursera quizzes + Final Exam (FE)", null, 1, 1, 0), HistoricalStats.empty());
        assertEquals(CourseWorkload.LEVEL_MEDIUM, medium.level());
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, medium.source());

        CourseWorkload heavy = CourseWorkloadEvaluator.evaluate(new WorkloadInput(
                "3", "CSD201", "Data Structures and Algorithms", 3, "CORE", "Core", null,
                "Lab 20% + Practical Exam (PE) 40% + Final Exam (FE) 40%", null, 2, 2, 4),
                HistoricalStats.empty());
        assertEquals(CourseWorkload.LEVEL_HEAVY, heavy.level());
        assertTrue(heavy.score() >= 65);
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, heavy.source());
    }

    @Test
    void layer1HandlesUppercaseCourseraAndNoFinalExamWithoutContradiction() {
        CourseWorkload coursera = CourseWorkloadEvaluator.evaluate(new WorkloadInput(
                "4", "ITE302C", "Ethics in IT", 3, "GEN", "General Education", null,
                "No final exam, Coursera completion", null, 0, 0, 0), HistoricalStats.empty());
        assertEquals(CourseWorkload.LEVEL_LIGHT, coursera.level());
        assertTrue(coursera.ruleReasons().stream().anyMatch(r -> r.contains("Coursera")));
        assertFalse(coursera.ruleReasons().stream().anyMatch(r -> r.contains("Final Exam (FE)")));
    }
}
