package vn.edu.fpt.faaps.advising.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.AdvisorSlot;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.Plan;
import vn.edu.fpt.faaps.advising.service.SmartAutoAssignPlanner.StudentCandidate;

class SmartAutoAssignPlannerTest {

    @Test
    void balancesByUtilizationRatioRatherThanAbsoluteCount() {
        UUID smallCap = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largeCap = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<AdvisorSlot> advisors = List.of(
                new AdvisorSlot(smallCap, 20, 18, 0), // 90% full
                new AdvisorSlot(largeCap, 60, 20, 0)  // 33% full
        );
        List<StudentCandidate> students = List.of(
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false)
        );

        Plan plan = SmartAutoAssignPlanner.plan(advisors, students, false);
        assertEquals(4, plan.assigned());
        assertEquals(0, plan.unassignedRemaining());
        assertEquals(0, plan.assignedByAdvisor().get(smallCap));
        assertEquals(4, plan.assignedByAdvisor().get(largeCap));
        assertFalse(plan.warning());
    }

    @Test
    void spreadsAtRiskStudentsEvenlyAcrossAdvisors() {
        UUID adv1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID adv2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<AdvisorSlot> advisors = List.of(
                new AdvisorSlot(adv1, 30, 5, 2),
                new AdvisorSlot(adv2, 30, 5, 0)
        );
        List<StudentCandidate> students = List.of(
                new StudentCandidate(UUID.randomUUID(), true),
                new StudentCandidate(UUID.randomUUID(), true),
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false)
        );

        Plan plan = SmartAutoAssignPlanner.plan(advisors, students, false);
        assertEquals(4, plan.assigned());
        assertEquals(2, plan.atRiskAssigned());
        long adv2Risk = plan.pairs().stream().filter(p -> p.advisorId().equals(adv2) && p.atRisk()).count();
        assertEquals(2, adv2Risk);
    }

    @Test
    void respectsMaxAdviseesByDefaultAndAllowsOverflowWhenEnabled() {
        UUID adv1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<AdvisorSlot> advisors = List.of(new AdvisorSlot(adv1, 2, 1, 0));
        List<StudentCandidate> students = List.of(
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false),
                new StudentCandidate(UUID.randomUUID(), false)
        );

        Plan capped = SmartAutoAssignPlanner.plan(advisors, students, false);
        assertEquals(1, capped.assigned());
        assertEquals(2, capped.unassignedRemaining());
        assertFalse(capped.warning());

        Plan overflow = SmartAutoAssignPlanner.plan(advisors, students, true);
        assertEquals(3, overflow.assigned());
        assertEquals(0, overflow.unassignedRemaining());
        assertTrue(overflow.warning());
    }

    @Test
    void detectsAtRiskFromStatusAndLowGpa() {
        assertTrue(SmartAutoAssignPlanner.isAtRisk("PROBATION", new BigDecimal("7.50")));
        assertTrue(SmartAutoAssignPlanner.isAtRisk("ACTIVE", new BigDecimal("4.50")));
        assertFalse(SmartAutoAssignPlanner.isAtRisk("ACTIVE", new BigDecimal("7.20")));
        assertFalse(SmartAutoAssignPlanner.isAtRisk("ACTIVE", BigDecimal.ZERO));
    }
}
