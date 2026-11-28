package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Covers the course substitution mechanism: a curriculum course that an admin declared
 * as replaced must be credited when the student passed the substitute course.
 */
class CurriculumSubstitutionServiceTest {
    private JdbcTemplate jdbc;
    private CurriculumSubstitutionService service;
    private UUID studentId;
    private UUID curriculumId;
    private UUID otherCurriculumId;
    private UUID semesterId;
    private UUID csi104;
    private UUID csi106;
    private UUID csi105;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:subst-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(100), email VARCHAR(100), phone VARCHAR(20), date_of_birth DATE, status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20), name VARCHAR(100), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), version VARCHAR(20), name VARCHAR(100), description CLOB, effective_from_semester_id VARCHAR(36), status VARCHAR(20))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20), name VARCHAR(100), start_date DATE)");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(20), major_id VARCHAR(36), curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36), expected_graduation_semester_id VARCHAR(36), current_semester INT, current_gpa DECIMAL(4,2), total_credits_earned INT, academic_status VARCHAR(20), updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE student_course_records (id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36), course_id VARCHAR(36), semester_id VARCHAR(36), final_score DECIMAL(4,2), status VARCHAR(20), attempt_number INT, credits_earned INT)");
        jdbc.execute("CREATE TABLE courses (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20), name VARCHAR(100), credits INT, category_id VARCHAR(36), description CLOB, objectives CLOB, learning_outcomes CLOB, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE course_categories (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE curriculum_courses (curriculum_version_id VARCHAR(36), course_id VARCHAR(36), semester_number INT, is_mandatory BOOLEAN, group_name VARCHAR(50))");
        jdbc.execute("CREATE TABLE semester_summaries (student_id VARCHAR(36), semester_id VARCHAR(36), total_credits_enrolled INT, total_credits_earned INT, semester_gpa DECIMAL(4,2), cumulative_gpa DECIMAL(4,2), academic_standing VARCHAR(50))");
        jdbc.execute("CREATE TABLE course_prerequisites (id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36), prerequisite_course_id VARCHAR(36), group_no INT, type VARCHAR(20), effective_from_curriculum_version_id VARCHAR(36), replaced_by_reason VARCHAR(255), minimum_score DECIMAL(4,2), created_at TIMESTAMP)");

        studentId = UUID.randomUUID();
        curriculumId = UUID.randomUUID();
        otherCurriculumId = UUID.randomUUID();
        semesterId = UUID.randomUUID();
        csi104 = UUID.randomUUID();
        csi106 = UUID.randomUUID();
        csi105 = UUID.randomUUID();

        jdbc.update("INSERT INTO users VALUES (?, 'Test', 'test@fpt.edu.vn', '0901234567', NULL, 'ACTIVE', CURRENT_TIMESTAMP)", studentId.toString());
        jdbc.update("INSERT INTO semesters VALUES (?, 'SU2023', 'Summer 2023', DATE '2023-05-01')", semesterId.toString());
        UUID majorId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K18', 'SE K18', 'Desc', ?, 'ACTIVE')", curriculumId.toString(), majorId.toString(), semesterId.toString());
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K19', 'SE K19', 'Desc', ?, 'ACTIVE')", otherCurriculumId.toString(), majorId.toString(), semesterId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, current_semester, current_gpa, total_credits_earned, academic_status) VALUES (?, 'SE180001', ?, ?, 1, 0, 0, 'ACTIVE')", studentId.toString(), majorId.toString(), curriculumId.toString());

        // The curriculum requires CSI106; the student actually took the older CSI104.
        jdbc.update("INSERT INTO courses VALUES (?, 'CSI104', 'Introduction to Computer', 3, NULL, NULL, NULL, NULL, 'ACTIVE')", csi104.toString());
        jdbc.update("INSERT INTO courses VALUES (?, 'CSI106', 'Introduction to Computer Science', 3, NULL, NULL, NULL, NULL, 'ACTIVE')", csi106.toString());
        jdbc.update("INSERT INTO courses VALUES (?, 'CSI105', 'Introduction to Computer Science', 3, NULL, NULL, NULL, NULL, 'ACTIVE')", csi105.toString());
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, 1, true, 'Major')", curriculumId.toString(), csi106.toString());
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, 1, true, 'Major')", otherCurriculumId.toString(), csi106.toString());
        jdbc.update("INSERT INTO student_course_records VALUES (?, ?, ?, ?, 6.60, 'PASSED', 1, 3)",
                UUID.randomUUID().toString(), studentId.toString(), csi104.toString(), semesterId.toString());

        service = new CurriculumSubstitutionService(jdbc, new CourseEquivalenceService(jdbc));
    }

    @Test
    void aRenumberedPredecessorIsInferredWithoutADeclaration() {
        // The catalog is rebuilt from FLM exports, so CSI104 -> CSI106 must be found from
        // the codes, names and credits alone: same subject, same level, same credits,
        // "Introduction to Computer" extended by "Science".
        List<CurriculumSubstitutionService.SatisfiedCourse> satisfied =
                service.satisfiedCurriculumCourses(studentId, curriculumId.toString());

        assertEquals(1, satisfied.size());
        assertEquals("CSI104", satisfied.get(0).fulfilledByCode());
        assertEquals(CurriculumSubstitutionService.MatchKind.INFERRED, satisfied.get(0).matchKind());
    }

    @Test
    void aHigherLevelCourseIsNeverInferred() {
        UUID basic = course("EBS211", "Hệ thống nhúng", 3);
        UUID advanced = course("EBS311", "Hệ thống nhúng nâng cao", 3);
        require(advanced);
        pass(basic);

        assertTrue(service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).stream()
                .noneMatch(c -> c.curriculumCode().equals("EBS311")));
    }

    @Test
    void aLevelQualifierInTheNameBlocksTheInference() {
        UUID tools = course("DTG102", "Visual Design Tools", 3);
        UUID tools1 = course("DTG111", "Visual Design Tools 1", 3);
        require(tools1);
        pass(tools);

        assertTrue(service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).stream()
                .noneMatch(c -> c.curriculumCode().equals("DTG111")));
    }

    @Test
    void anAmbiguousPredecessorIsNotInferred() {
        // CSI104 could have been replaced by CSI105 or CSI106: both are open, so neither
        // is credited.
        require(csi105);

        assertTrue(service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).isEmpty());
    }

    @Test
    void aCodeVariantWithAnUnrelatedNameIsNotEquivalent() {
        UUID erp = course("ISM301", "ERP Systems", 3);
        UUID strategy = course("ISM301c", "International Strategic Management", 3);
        require(erp);
        pass(strategy);

        assertTrue(service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).stream()
                .noneMatch(c -> c.curriculumCode().equals("ISM301")));
    }

    @Test
    void aCodeVariantOfTheSameSubjectIsEquivalent() {
        UUID plain = course("SWE201", "Introduction to Software Engineering", 3);
        UUID coursera = course("SWE201c", "Introduction to Software Engineering", 3);
        require(coursera);
        pass(plain);

        var match = service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).stream()
                .filter(c -> c.curriculumCode().equals("SWE201c")).findFirst().orElseThrow();
        assertEquals("SWE201", match.fulfilledByCode());
        assertEquals(CurriculumSubstitutionService.MatchKind.EQUIVALENT, match.matchKind());
    }

    @Test
    void theStudentEquivalentsIncludeTheInferredReplacement() {
        var equivalents = service.curriculumEquivalents(studentId, curriculumId.toString());

        assertTrue(equivalents.get("CSI106").contains("CSI104"));
        assertTrue(equivalents.get("CSI104").contains("CSI106"));
    }

    @Test
    void declaredSubstitutionSatisfiesTheCurriculumRequirement() {
        declareReplacement(csi106, csi104, curriculumId);

        List<CurriculumSubstitutionService.SatisfiedCourse> satisfied =
                service.satisfiedCurriculumCourses(studentId, curriculumId.toString());

        assertEquals(1, satisfied.size());
        CurriculumSubstitutionService.SatisfiedCourse course = satisfied.get(0);
        assertEquals("CSI106", course.curriculumCode());
        assertEquals("CSI104", course.fulfilledByCode());
        assertTrue(course.viaSubstitute());
        assertEquals(CurriculumSubstitutionService.MatchKind.DECLARED, course.matchKind());
        assertEquals(1, service.passedCurriculumCourseCount(studentId, curriculumId.toString()));
    }

    @Test
    void aSubstitutionScopedToAnotherCurriculumDoesNotApply() {
        declareReplacement(csi106, csi104, otherCurriculumId);

        var satisfied = service.satisfiedCurriculumCourses(studentId, curriculumId.toString());
        assertEquals(CurriculumSubstitutionService.MatchKind.INFERRED, satisfied.get(0).matchKind(),
                "A substitution scoped to a different cohort must not be reported as declared for this one");
    }

    @Test
    void anUnscopedSubstitutionAppliesToEveryCurriculum() {
        declareReplacement(csi106, csi104, null);

        assertEquals(1, service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).size());
        assertEquals(1, service.satisfiedCurriculumCourses(studentId, otherCurriculumId.toString()).size());
    }

    @Test
    void aFailedSubstituteDoesNotSatisfyTheRequirement() {
        declareReplacement(csi106, csi104, curriculumId);
        jdbc.update("UPDATE student_course_records SET status = 'FAILED' WHERE student_id = ?", studentId.toString());

        assertTrue(service.satisfiedCurriculumCourses(studentId, curriculumId.toString()).isEmpty());
    }

    @Test
    void theEquivalenceGraphOfTheCurriculumLinksBothCodes() {
        declareReplacement(csi106, csi104, curriculumId);

        var equivalents = service.curriculumEquivalents(curriculumId.toString());

        assertTrue(equivalents.containsKey("CSI106"));
        assertTrue(equivalents.get("CSI106").contains("CSI104"));
        assertTrue(equivalents.containsKey("CSI104"));
        assertTrue(equivalents.get("CSI104").contains("CSI106"));
    }

    @Test
    void structuralEvidenceIsSuggestedToTheAdmin() {
        // CSI104 and CSI106 share the prefix and the credits, and their names are
        // near-identical, so the candidate is proposed to the admin as well.
        var suggestions = new CourseEquivalenceService(jdbc).suggestSubstitutions(csi106.toString());

        assertTrue(suggestions.stream().anyMatch(s -> s.code().equals("CSI104")),
                "CSI104 must be proposed as a replacement candidate for CSI106");
        assertTrue(suggestions.stream().anyMatch(s -> s.code().equals("CSI105")),
                "CSI105 must be proposed as a replacement candidate for CSI106");
    }

    @Test
    void theCurriculumCourseKeepsItsOwnCodeAndReportsTheSubstitute() {
        declareReplacement(csi106, csi104, curriculumId);

        var academic = new StudentAcademicService(jdbc);
        var courses = academic.curriculumCourses(studentId);

        assertEquals(1, courses.size());
        assertEquals("CSI106", courses.get(0).code());
        assertEquals("CSI104", courses.get(0).satisfiedViaCode());
    }

    @Test
    void progressCountsTheRequirementAsCompleted() {
        declareReplacement(csi106, csi104, curriculumId);

        var academic = new StudentAcademicService(jdbc);

        assertEquals(1, academic.progress(studentId).completedCourses());
    }

    @Test
    void progressCountsTheInferredReplacement() {
        var academic = new StudentAcademicService(jdbc);

        assertEquals(1, academic.progress(studentId).completedCourses());
    }

    @Test
    void progressIgnoresAFailedPredecessor() {
        jdbc.update("UPDATE student_course_records SET status = 'FAILED' WHERE student_id = ?", studentId.toString());
        var academic = new StudentAcademicService(jdbc);

        assertEquals(0, academic.progress(studentId).completedCourses());
    }

    @Test
    void theCurriculumCourseSatisfiedByIsResolvedForTheRecord() {
        declareReplacement(csi106, csi104, curriculumId);

        assertEquals(csi106.toString(),
                service.curriculumCourseSatisfiedBy(csi104.toString(), curriculumId.toString()));
        assertNull(service.curriculumCourseSatisfiedBy(csi106.toString(), curriculumId.toString()));
    }

    @Test
    void theDeclaredSubstitutesAreListedForTheCurriculum() {
        declareReplacement(csi106, csi104, curriculumId);

        var substitutes = service.declaredSubstitutes(curriculumId.toString());

        assertEquals(1, substitutes.size());
        assertTrue(substitutes.get(csi106.toString()).contains(csi104.toString()));
        assertFalse(substitutes.containsKey(csi104.toString()));
    }

    @Test
    void theCourseStillShowsInTheCurriculum() {
        declareReplacement(csi106, csi104, curriculumId);

        var academic = new StudentAcademicService(jdbc);
        var courses = academic.curriculumCourses(studentId);

        assertNotNull(courses.get(0).satisfiedViaCode());
    }

    private UUID course(String code, String name, int credits) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO courses VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, 'ACTIVE')",
                id.toString(), code, name, credits);
        return id;
    }

    private void require(UUID courseId) {
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, 2, true, 'Major')", curriculumId.toString(), courseId.toString());
    }

    private void pass(UUID courseId) {
        jdbc.update("INSERT INTO student_course_records VALUES (?, ?, ?, ?, 7.00, 'PASSED', 1, 3)",
                UUID.randomUUID().toString(), studentId.toString(), courseId.toString(), semesterId.toString());
    }

    private void declareReplacement(UUID replaced, UUID replacement, UUID scope) {
        jdbc.update("""
                INSERT INTO course_prerequisites
                    (id, course_id, prerequisite_course_id, group_no, type,
                     effective_from_curriculum_version_id, replaced_by_reason, minimum_score, created_at)
                VALUES (?, ?, ?, 1, 'REPLACEMENT', ?, 'curriculum change', NULL, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), replaced.toString(), replacement.toString(),
                scope == null ? null : scope.toString());
    }
}
