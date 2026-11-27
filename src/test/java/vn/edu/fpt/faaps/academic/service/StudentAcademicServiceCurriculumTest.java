package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class StudentAcademicServiceCurriculumTest {
    private JdbcTemplate jdbc;
    private StudentAcademicService service;
    private UUID studentId;
    private UUID cur1;
    private UUID cur2;
    private UUID majorId;
    private UUID semId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:acad-cur-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
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

        studentId = UUID.randomUUID();
        majorId = UUID.randomUUID();
        cur1 = UUID.randomUUID();
        cur2 = UUID.randomUUID();
        semId = UUID.randomUUID();

        jdbc.update("INSERT INTO users VALUES (?, 'Test Student', 'test@fpt.edu.vn', '0901234567', NULL, 'ACTIVE', CURRENT_TIMESTAMP)", studentId.toString());
        jdbc.update("INSERT INTO majors VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO semesters VALUES (?, 'FA2026', 'Fall 2026', DATE '2026-09-01')", semId.toString());
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K19', 'SE K19', 'Desc', ?, 'ACTIVE')", cur1.toString(), majorId.toString(), semId.toString());
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K20', 'SE K20', 'Desc', ?, 'ACTIVE')", cur2.toString(), majorId.toString(), semId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id, current_semester, current_gpa, total_credits_earned, academic_status) VALUES (?, 'SE190001', NULL, NULL, NULL, 1, 0, 0, 'ACTIVE')", studentId.toString());

        service = new StudentAcademicService(jdbc);
    }

    @Test
    void studentCanChooseCurriculumWhenUnassigned() {
        StudentAcademicService.Profile initial = service.profile(studentId);
        assertNull(initial.curriculumVersionId());
        assertNull(initial.majorCode());
        assertNull(service.curriculum(studentId));
        assertEquals(0, service.curriculumCourses(studentId).size());

        StudentAcademicService.Profile updated = service.changeCurriculum(studentId, cur1);
        assertEquals(cur1.toString(), updated.curriculumVersionId());
        assertEquals("SE", updated.majorCode());
        assertEquals("K19", updated.cohort());

        assertNotNull(service.curriculum(studentId));
    }

    @Test
    void rejectsUnavailableOrNonExistentCurriculum() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.changeCurriculum(studentId, UUID.randomUUID()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void blocksCurriculumChangeIfTranscriptAlreadyHasGrades() {
        service.changeCurriculum(studentId, cur1);

        // Record a completed course with grade
        jdbc.update("INSERT INTO student_course_records VALUES (?, ?, 'c1', ?, 8.50, 'PASSED', 1, 3)",
                UUID.randomUUID().toString(), studentId.toString(), semId.toString());

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                service.changeCurriculum(studentId, cur2));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @Test
    void recalculatesExpectedGraduationSemesterWhenCurriculumChosen() {
        UUID gradSemId = UUID.randomUUID();
        // Setup semesters: student enrolled in SP2026, current semester is FA2026, student is in term 1 (2 terms behind)
        // 7-term curriculum finishes in FA2028 (SP26 -> FA28 = 8 terms total with delay)
        UUID enrollmentSemId = UUID.randomUUID();
        jdbc.update("INSERT INTO semesters VALUES (?, 'SP2026', 'Spring 2026', DATE '2026-01-01')", enrollmentSemId.toString());
        jdbc.update("INSERT INTO semesters VALUES (?, 'FA2028', 'Fall 2028', DATE '2028-09-01')", gradSemId.toString());
        for (int term = 1; term <= 7; term++) {
            jdbc.update("INSERT INTO curriculum_courses (curriculum_version_id, course_id, semester_number, is_mandatory) VALUES (?, ?, ?, true)",
                    cur1.toString(), UUID.randomUUID().toString(), term);
        }
        // Update cur1 effective from enrollmentSemId
        jdbc.update("UPDATE curriculum_versions SET effective_from_semester_id = ? WHERE id = ?",
                enrollmentSemId.toString(), cur1.toString());

        service.changeCurriculum(studentId, cur1);

        String computedGradSem = jdbc.queryForObject(
                "SELECT expected_graduation_semester_id FROM students WHERE user_id = ?",
                String.class, studentId.toString());
        assertEquals(gradSemId.toString(), computedGradSem);
    }

    @Test
    void curriculumCoursesIncludeEvaluatedWorkload() {
        UUID catId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories VALUES (?, 'Major Core')", catId.toString());
        jdbc.update("INSERT INTO courses VALUES (?, 'PRN212', 'Basic Cross-Platform App Programming', 3, ?, 'Desc', 'Obj', 'LO', 'ACTIVE')",
                courseId.toString(), catId.toString());
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, 5, true, 'Core')", cur1.toString(), courseId.toString());
        service.changeCurriculum(studentId, cur1);

        var courses = service.curriculumCourses(studentId);
        assertEquals(1, courses.size());
        var cc = courses.get(0);
        assertEquals("PRN212", cc.code());
        assertNotNull(cc.workload());
        assertNotNull(cc.workload().level());
        assertEquals("RULE_BASED", cc.workload().source());
    }

    @Test
    void unresolvedFlmPlaceholderFallbackDoesNotLeakPlaceholderWorkload() {
        UUID catId = UUID.randomUUID();
        UUID placeholderId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories VALUES (?, 'Elective')", catId.toString());
        jdbc.update("INSERT INTO courses VALUES (?, 'TMI_ELE', 'Musical Instrument Placeholder', 3, ?, 'Desc', 'Obj', 'LO', 'ACTIVE')",
                placeholderId.toString(), catId.toString());
        jdbc.update("INSERT INTO curriculum_courses VALUES (?, ?, 1, false, 'Instrument')",
                cur1.toString(), placeholderId.toString());
        service.changeCurriculum(studentId, cur1);

        // Choose an instrument code that does not exist in courses table -> falls back to placeholderId
        new StudentFlmPreferencesService(jdbc).save(studentId, "SE", null, null, "TMI999", null);

        var courses = service.curriculumCourses(studentId);
        assertEquals(1, courses.size());
        assertEquals("TMI999", courses.get(0).code());
        assertEquals(placeholderId.toString(), courses.get(0).courseId());
        assertNull(courses.get(0).workload());
    }
}
