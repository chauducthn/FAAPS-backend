package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.academic.service.CourseMatchRules.CourseRef;

/** The code-level rules that replace hand-written equivalence data. */
class CourseMatchRulesTest {
    private static CourseRef c(String code, String name, int credits) {
        return new CourseRef(code, code, name, credits);
    }

    @Test
    void theBaseCodeIgnoresCaseAndTheDeliverySuffix() {
        assertEquals("IOT102", CourseMatchRules.baseCode("IoT102t"));
        assertEquals("SWE201", CourseMatchRules.baseCode("SWE201c"));
        assertEquals("DBI202", CourseMatchRules.baseCode("DBI202-OLD"));
        assertNull(CourseMatchRules.baseCode("SE_COM*4"));
        assertNull(CourseMatchRules.baseCode("TMI_ELE"));
    }

    @Test
    void codeVariantsNeedTheSameCreditsAndName() {
        assertTrue(CourseMatchRules.isSameCourseVariant(
                c("DPL303m", "Deep Learning", 3), c("DPL303", "Deep learning", 3)));
        assertTrue(CourseMatchRules.isSameCourseVariant(
                c("IMP302m", "Image & Video processing", 3), c("IMP302", "Image and Video processing", 3)));
        assertFalse(CourseMatchRules.isSameCourseVariant(
                c("ISM301", "ERP Systems", 3), c("ISM301c", "International Strategic Management", 3)));
        assertFalse(CourseMatchRules.isSameCourseVariant(
                c("IAP491", "IA Graduation Project", 10), c("IAP491t", "IA Graduation Project", 3)));
    }

    @Test
    void replacementsStayInTheSameSubjectLevelAndCredits() {
        assertTrue(CourseMatchRules.isLikelyReplacement(
                c("CSI104", "Introduction to Computer", 3), c("CSI106", "Introduction to Computer Science", 3)));
        assertTrue(CourseMatchRules.isLikelyReplacement(
                c("PRF192", "Programming Fundamentals", 3), c("PRF193", "Programming Fundamentals", 3)));
        assertFalse(CourseMatchRules.isLikelyReplacement(
                c("EBS211", "Hệ thống nhúng", 3), c("EBS311", "Hệ thống nhúng nâng cao", 3)));
        assertFalse(CourseMatchRules.isLikelyReplacement(
                c("DTG102", "Visual Design Tools", 3), c("DTG111", "Visual Design Tools 1", 3)));
        assertFalse(CourseMatchRules.isLikelyReplacement(
                c("CSI104", "Introduction to Computer", 3), c("CSI106", "Introduction to Computer Science", 2)));
        assertFalse(CourseMatchRules.isLikelyReplacement(
                c("CSI104", "Introduction to Computer", 3), c("CSD104", "Introduction to Computer", 3)));
    }
}
